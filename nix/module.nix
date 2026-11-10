self:
{ config, lib, pkgs, ... }:

let
  cfg = config.services.podzod;
  str = s: ''"${lib.escape [ ''"'' "\\" ] s}"'';
  generated = pkgs.writeText "podzod.edn" ''
    {:port ${toString cfg.port}
     :bind ${str cfg.bind}
     :db "/var/lib/podzod/podzod.db"
     :user ${str cfg.user}
     :password-file "/run/credentials/podzod.service/password"
     :lms {:url ${str cfg.lmsUrl}${lib.optionalString (cfg.defaultPlayer != null) " :default-player ${str cfg.defaultPlayer}"}}
     ${cfg.extraConfig}}
  '';
in
{
  options.services.podzod = {
    enable = lib.mkEnableOption "podzod podcast sync and picker";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
    };

    port = lib.mkOption { type = lib.types.port; default = 8086; };
    bind = lib.mkOption { type = lib.types.str; default = "0.0.0.0"; };
    openFirewall = lib.mkOption { type = lib.types.bool; default = false; };

    user = lib.mkOption {
      type = lib.types.str;
      description = "The single gpodder username (what you type into AntennaPod).";
    };

    passwordFile = lib.mkOption {
      type = lib.types.path;
      description = "File holding the password. Read via systemd LoadCredential, so it can be root-only.";
    };

    lmsUrl = lib.mkOption { type = lib.types.str; default = "http://localhost:9000"; };

    defaultPlayer = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      description = "LMS player id (MAC) for /next and /skip when none is given.";
    };

    extraConfig = lib.mkOption {
      type = lib.types.lines;
      default = "";
      example = ''
        :defaults {:news-max-age-days 5}
        :feeds {"https://example.com/story.xml" {:serial true}}
      '';
      description = "Extra EDN key/value pairs for the top-level config map. Don't repeat keys set above.";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.services.podzod = {
      description = "podzod";
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];
      serviceConfig = {
        ExecStart = "${cfg.package}/bin/podzod ${generated}";
        DynamicUser = true;
        StateDirectory = "podzod";
        LoadCredential = [ "password:${cfg.passwordFile}" ];
        Restart = "on-failure";
        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
      };
    };

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];
  };
}
