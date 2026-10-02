# NixOS module for the sorchat chat server, behind nginx, next to coturn.nix.
# Exported by this repo's flake as nixosModules.default, with the server package built from source:
#
#   inputs.sorchat.url = "git+https://…/sorchat";
#   modules = [
#     sorchat.nixosModules.default
#     {
#       services.sorchat = {
#         enable = true;
#         domain = "chat.urbit.men";
#         fcmCredentialsFile = "/run/secrets/sorchat-fcm.json";
#       };
#     }
#   ];
#
# Secrets only need to be readable by root: systemd hands them to the service as credentials.
{
  config,
  lib,
  pkgs,
  ...
}: let
  cfg = config.services.sorchat;
  inherit (lib) mkEnableOption mkIf mkOption types;
in {
  options.services.sorchat = {
    enable = mkEnableOption "the sorchat chat server";

    package = mkOption {
      type = types.package;
      description = "The sorchat server package (nix/sorchat-server.nix; the flake's module sets it).";
    };

    domain = mkOption {
      type = types.str;
      example = "chat.example.com";
      description = "Domain nginx serves the chat server on, with a Let's Encrypt certificate.";
    };

    port = mkOption {
      type = types.port;
      default = 8090;
      description = "Port the server listens on, on localhost only; nginx proxies to it.";
    };

    fcmCredentialsFile = mkOption {
      type = types.nullOr types.path;
      default = null;
      description = "Firebase service-account JSON for push notifications. Without it, pushes are only logged.";
    };

    backup = {
      enable = mkEnableOption "daily backups of the sorchat database and media";

      directory = mkOption {
        type = types.path;
        default = "/var/backup/sorchat";
        description = ''
          Where backups go, one timestamped directory each. Copy them off the machine too
          (e.g. with services.restic or services.borgbackup) to survive losing the VPS.
        '';
      };

      schedule = mkOption {
        type = types.str;
        default = "daily";
        example = "*-*-* 04:00:00";
        description = "When to back up, as a systemd OnCalendar expression.";
      };

      keepDays = mkOption {
        type = types.ints.positive;
        default = 14;
        description = "Backups older than this many days are deleted.";
      };
    };

    turn = {
      domain = mkOption {
        type = types.nullOr types.str;
        default = if config.services.coturn.enable then config.services.coturn.realm else null;
        defaultText = lib.literalExpression "config.services.coturn.realm, if coturn is enabled";
        description = "coturn's domain. Without it (and a secret), only a public STUN server is offered.";
      };

      secretFile = mkOption {
        type = types.nullOr types.path;
        default = if config.services.coturn.enable then config.services.coturn.static-auth-secret-file else null;
        defaultText = lib.literalExpression "config.services.coturn.static-auth-secret-file, if coturn is enabled";
        description = "coturn's static-auth-secret, used to issue short-lived TURN credentials.";
      };
    };
  };

  config = mkIf cfg.enable {
    # Restore: stop sorchat, copy a backup's sorchat.db and media/ to /var/lib/private/sorchat/,
    # start sorchat (systemd fixes the ownership).
    systemd.services.sorchat-backup = mkIf cfg.backup.enable {
      description = "Back up the sorchat database and media";
      path = [pkgs.sqlite pkgs.coreutils pkgs.findutils];
      script = ''
        set -euo pipefail
        dest=${lib.escapeShellArg cfg.backup.directory}/$(date +%Y-%m-%d_%H%M%S)
        mkdir -p "$dest"
        # SQLite's online backup gives a consistent copy while the server keeps writing.
        sqlite3 /var/lib/private/sorchat/sorchat.db ".backup '$dest/sorchat.db'"
        if [ -d /var/lib/private/sorchat/media ]; then
          cp -a --reflink=auto /var/lib/private/sorchat/media "$dest/"
        fi
        find ${lib.escapeShellArg cfg.backup.directory} -mindepth 1 -maxdepth 1 -type d \
          -mtime +${toString cfg.backup.keepDays} -exec rm -rf {} +
        echo "Backed up to $dest ($(du -sh "$dest" | cut -f1))"
      '';
      serviceConfig = {
        Type = "oneshot";
        # Backups hold everyone's queued messages: root only.
        UMask = "0077";
        Nice = 10;
        IOSchedulingClass = "idle";
      };
    };

    systemd.timers.sorchat-backup = mkIf cfg.backup.enable {
      wantedBy = ["timers.target"];
      timerConfig = {
        OnCalendar = cfg.backup.schedule;
        # Catch up after the machine was off at the scheduled time.
        Persistent = true;
        RandomizedDelaySec = "15m";
      };
    };

    systemd.services.sorchat = {
      description = "sorchat chat server";
      wantedBy = ["multi-user.target"];
      wants = ["network-online.target"];
      after = ["network-online.target"];

      environment =
        {
          HOST = "127.0.0.1";
          PORT = toString cfg.port;
          SORCHAT_DB = "/var/lib/sorchat/sorchat.db";
          SORCHAT_MEDIA_DIR = "/var/lib/sorchat/media";
        }
        # %d is the directory where systemd puts the LoadCredential files below.
        // lib.optionalAttrs (cfg.fcmCredentialsFile != null) {
          SORCHAT_FCM_CREDENTIALS = "%d/fcm.json";
        }
        // lib.optionalAttrs (cfg.turn.domain != null && cfg.turn.secretFile != null) {
          SORCHAT_TURN_SECRET_FILE = "%d/turn-secret";
          SORCHAT_TURN_URLS = lib.concatStringsSep "," [
            "stun:${cfg.turn.domain}:3478"
            "turn:${cfg.turn.domain}:3478?transport=udp"
            "turn:${cfg.turn.domain}:3478?transport=tcp"
            "turns:${cfg.turn.domain}:5349?transport=tcp"
          ];
        };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        Restart = "on-failure";
        DynamicUser = true;
        StateDirectory = "sorchat";
        WorkingDirectory = "/var/lib/sorchat";
        LoadCredential =
          lib.optional (cfg.fcmCredentialsFile != null) "fcm.json:${cfg.fcmCredentialsFile}"
          ++ lib.optional (cfg.turn.domain != null && cfg.turn.secretFile != null) "turn-secret:${cfg.turn.secretFile}";

        # Hardening: the server only needs its state directory and the network.
        NoNewPrivileges = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
        RestrictAddressFamilies = ["AF_INET" "AF_INET6" "AF_UNIX"];
        RestrictNamespaces = true;
        LockPersonality = true;
        SystemCallArchitectures = "native";
      };
    };

    services.nginx.virtualHosts.${cfg.domain} = {
      enableACME = true;
      forceSSL = true;
      locations."/" = {
        proxyPass = "http://127.0.0.1:${toString cfg.port}";
        # The app's live connection is a WebSocket.
        proxyWebsockets = true;
        extraConfig = ''
          # Voice notes up to 16 MB (nginx's default is 1 MB).
          client_max_body_size 17m;
          # The WebSocket is pinged every 30 s; don't cut it off in between (default 60 s).
          proxy_read_timeout 120s;
        '';
      };
    };
  };
}
