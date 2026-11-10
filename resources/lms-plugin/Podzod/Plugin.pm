package Plugins::Podzod::Plugin;

# Thin client for the podzod service: menus and actions come from podzod,
# this reports playback progress, resumes podcasts, and lets podzod reach
# BBC Sounds through the BBC Sounds plugin's signed-in session.

use strict;
use base qw(Slim::Plugin::OPMLBased);

use JSON::XS::VersionOneAndTwo;
use MIME::Base64 qw(encode_base64);
use URI::Escape qw(uri_escape_utf8);

use Slim::Networking::SimpleAsyncHTTP;
use Slim::Utils::Log;
use Slim::Utils::Prefs;
use Slim::Utils::Timers;

use constant REPORT_INTERVAL => 30;

my $log = Slim::Utils::Log->addLogCategory({
	category     => 'plugin.podzod',
	defaultLevel => 'WARN',
	description  => 'PLUGIN_PODZOD',
});

my $prefs = preferences('plugin.podzod');

# podzod fills these in when it builds the zip for you.
$prefs->init({
	server   => '__PODZOD_SERVER__',
	user     => '__PODZOD_USER__',
	password => '',
});

# client id => { url => ..., position => ..., duration => ... }
my %playing;

sub initPlugin {
	my $class = shift;

	if (main::WEBUI) {
		require Plugins::Podzod::Settings;
		Plugins::Podzod::Settings->new;
	}

	$class->SUPER::initPlugin(
		feed   => \&handleFeed,
		tag    => 'podzod',
		menu   => 'apps',
		is_app => 1,
		weight => 10,
	);

	Slim::Control::Request::addDispatch(['podzod', 'bbc', '_offset'], [0, 1, 1, \&cliBBC]);
	Slim::Control::Request::subscribe(\&onPlaylistEvent, [['playlist'], ['newsong', 'pause', 'stop']]);
}

sub getDisplayName { 'PLUGIN_PODZOD' }

sub playerMenu { }

# HTTP to podzod

sub _headers {
	my $auth = encode_base64($prefs->get('user') . ':' . $prefs->get('password'), '');
	return ('Authorization' => "Basic $auth");
}

sub _url {
	my ($path, $params) = @_;
	my $url = $prefs->get('server') . $path;
	if ($params && %$params) {
		$url .= '?' . join('&', map { $_ . '=' . uri_escape_utf8($params->{$_}) } grep { defined $params->{$_} } sort keys %$params);
	}
	return $url;
}

sub _handler {
	my ($cb, $ecb) = @_;
	return (
		sub {
			my $http = shift;
			my $data = eval { from_json(${ $http->contentRef }) };
			return $ecb->("bad response from podzod: $@") if $@;
			$cb->($data);
		},
		sub {
			my ($http, $error) = @_;
			# podzod puts a readable message in the body for 4xx/5xx
			my $data = eval { from_json(${ $http->contentRef }) };
			$ecb->(($data && $data->{error}) || $error);
		},
	);
}

sub podzodGet {
	my ($path, $params, $cb, $ecb) = @_;
	Slim::Networking::SimpleAsyncHTTP->new(_handler($cb, $ecb), { timeout => 30 })
		->get(_url($path, $params), _headers());
}

sub podzodPost {
	my ($path, $body, $cb, $ecb) = @_;
	Slim::Networking::SimpleAsyncHTTP->new(_handler($cb, $ecb), { timeout => 30 })
		->post(_url($path), _headers(), 'Content-Type' => 'application/json', to_json($body));
}

# Menus: podzod returns {title, items: [{name, type: menu|action|text, menu|items|action}]}

sub handleFeed {
	my ($client, $cb, $args, $params) = @_;

	podzodGet('/lms/menu', $params || {}, sub {
		my $data = shift;
		$cb->({ title => $data->{title}, items => [ map { _item($_) } @{ $data->{items} || [] } ] });
	}, sub {
		$cb->({ items => [{ name => "podzod: $_[0]", type => 'text' }] });
	});
}

sub _item {
	my $item = shift;

	if ($item->{type} eq 'action') {
		return {
			name        => $item->{name},
			type        => 'link',
			url         => \&doAction,
			passthrough => [ $item->{action} ],
			nextWindow  => 'refresh',
		};
	}
	if ($item->{type} eq 'menu' && $item->{items}) {
		return { name => $item->{name}, type => 'link', items => [ map { _item($_) } @{ $item->{items} } ] };
	}
	if ($item->{type} eq 'menu') {
		return { name => $item->{name}, type => 'link', url => \&handleFeed, passthrough => [ $item->{menu} ] };
	}
	return { name => $item->{name}, type => 'text' };
}

sub doAction {
	my ($client, $cb, $args, $action) = @_;

	my $show = sub {
		my $msg = shift;
		$client->showBriefly({ line => [ 'Podzod', $msg ] }, { duration => 4 }) if $client;
		$cb->({ items => [{ name => $msg, type => 'text', showBriefly => 1, nextWindow => 'parent' }] });
	};

	podzodPost('/lms/action', { %$action, player => $client ? $client->id : undef },
		sub { $show->($_[0]->{message}) },
		sub { $show->("Failed: $_[0]") });
}

# Playback reporting

sub _isRemote {
	my $url = shift;
	return $url && $url =~ m{^(https?|sounds)://}i;
}

sub _snapshot {
	my $client = shift;
	my $song = $client->playingSong() or return;
	my $url = $song->currentTrack() ? $song->currentTrack()->url : undef;
	return unless _isRemote($url);
	return {
		url      => $url,
		position => Slim::Player::Source::songTime($client),
		duration => $song->duration(),
	};
}

sub _report {
	my ($client, $snap) = @_;
	return unless $snap && $snap->{url};
	podzodPost('/lms/progress', { player => $client->id, %$snap },
		sub { },
		sub { main::INFOLOG && $log->is_info && $log->info("progress report failed: $_[0]") });
}

sub _tick {
	my $client = shift;
	Slim::Utils::Timers::killTimers($client, \&_tick);
	return unless $client->isPlaying();
	if (my $snap = _snapshot($client)) {
		$playing{ $client->id } = $snap;
		_report($client, $snap);
	}
	Slim::Utils::Timers::setTimer($client, time() + REPORT_INTERVAL, \&_tick);
}

sub onPlaylistEvent {
	my $request = shift;
	my $client = $request->client() or return;
	my $event = $request->getRequest(1);

	if ($event eq 'newsong') {
		# Final word on the previous track, from the last tick.
		my $prev = delete $playing{ $client->id };
		my $snap = _snapshot($client);
		_report($client, $prev) if $prev && (!$snap || $prev->{url} ne $snap->{url});

		return unless $snap;
		$playing{ $client->id } = $snap;

		# BBC Sounds resumes itself via ?offset=; podcasts we seek.
		if ($snap->{url} =~ m{^https?://}i) {
			my $url = $snap->{url};
			podzodGet('/lms/resume', { url => $url }, sub {
				my $pos = $_[0]->{position};
				my $now = _snapshot($client);
				if ($pos && $now && $now->{url} eq $url && $now->{position} < $pos) {
					main::INFOLOG && $log->is_info && $log->info("resuming $url at $pos");
					$client->execute(['time', $pos]);
				}
			}, sub { });
		}
		Slim::Utils::Timers::killTimers($client, \&_tick);
		Slim::Utils::Timers::setTimer($client, time() + REPORT_INTERVAL, \&_tick);
	}
	elsif ($event eq 'pause' || $event eq 'stop') {
		my $snap = _snapshot($client) || $playing{ $client->id };
		_report($client, $snap) if $snap;
		Slim::Utils::Timers::killTimers($client, \&_tick);
		if ($event eq 'pause' && !$request->getParam('_newvalue')) {
			# unpaused
			Slim::Utils::Timers::setTimer($client, time() + REPORT_INTERVAL, \&_tick);
		}
	}
}

# `podzod bbc <offset>`: BBC Sounds followed programmes, raw JSON, using the
# BBC Sounds plugin's session (its cookies live in LMS's shared cookie jar).

sub cliBBC {
	my $request = shift;
	my $offset = $request->getParam('_offset') || 0;

	unless (defined &Plugins::BBCSounds::SessionManagement::renewSession) {
		$request->addResult('error', 'BBC Sounds plugin not installed');
		$request->setStatusDone();
		return;
	}

	$request->setStatusProcessing();

	Plugins::BBCSounds::SessionManagement::renewSession(
		sub {
			Slim::Networking::SimpleAsyncHTTP->new(
				sub {
					$request->addResult('json', ${ $_[0]->contentRef });
					$request->setStatusDone();
				},
				sub {
					$request->addResult('error', $_[1]);
					$request->setStatusDone();
				},
				{ timeout => 30 }
			)->get('https://rms.api.bbc.co.uk/v2/my/programmes/follows/playable?offset=' . int($offset));
		},
		sub {
			$request->addResult('error', 'BBC Sounds is not signed in');
			$request->setStatusDone();
		}
	);
}

1;
