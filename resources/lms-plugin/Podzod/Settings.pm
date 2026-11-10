package Plugins::Podzod::Settings;

use strict;
use base qw(Slim::Web::Settings);

use Slim::Utils::Prefs;

my $prefs = preferences('plugin.podzod');

sub name { 'PLUGIN_PODZOD' }

sub page { 'plugins/Podzod/settings/basic.html' }

sub prefs { ($prefs, qw(server user password)) }

1;
