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
#         passkeys.androidCertFingerprints = [ "02:AE:…" ];   # ./gradlew :app:signingReport
#         backup.enable = true;
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

  # Copies the database (SQLite's online backup: consistent while the server keeps writing) and
  # media into the directory given as $1. Shared by the local and offsite backups.
  snapshot = pkgs.writeShellScript "sorchat-snapshot" ''
    set -euo pipefail
    export PATH=${lib.makeBinPath [pkgs.sqlite pkgs.coreutils]}
    dest=$1
    rm -rf "$dest"
    mkdir -p "$dest"
    chmod 700 "$dest"
    sqlite3 /var/lib/private/sorchat/sorchat.db ".backup '$dest/sorchat.db'"
    if [ -d /var/lib/private/sorchat/media ]; then
      cp -a --reflink=auto /var/lib/private/sorchat/media "$dest/"
    fi
  '';
  offsiteStaging = "/var/backup/sorchat-offsite-staging";
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

    passkeys = {
      androidCertFingerprints = mkOption {
        type = types.listOf types.str;
        default = [];
        example = ["02:AE:F3:83:0F:1D:1D:6D:9F:15:1B:AC:3D:7A:61:0C:6E:C2:5F:EE:D0:69:BF:94:80:C3:78:03:1A:E1:90:99"];
        description = ''
          SHA-256 fingerprints of the certificates the Android app is signed with
          (`./gradlew :app:signingReport`). Enables passkey sign-in for `domain`; empty disables it.
        '';
      };

      androidPackage = mkOption {
        type = types.str;
        default = "one.yago.sorchat";
        description = "The Android app's package name.";
      };
    };

    backup = {
      enable = mkEnableOption "daily backups of the sorchat database and media";

      directory = mkOption {
        type = types.path;
        default = "/var/backup/sorchat";
        description = ''
          Where local backups go, one timestamped directory each. These are for quick restores;
          they don't survive losing the machine, which is what `backup.offsite` is for.
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
        description = "Local backups older than this many days are deleted.";
      };

      offsite = {
        repository = mkOption {
          type = types.nullOr types.str;
          default = null;
          example = "s3:https://s3.eu-central-1.amazonaws.com/my-bucket/sorchat";
          description = ''
            restic repository to copy backups to, e.g. an S3-compatible bucket (AWS, Backblaze B2,
            Cloudflare R2, Hetzner Object Storage, MinIO). Backups are encrypted before they leave
            the machine. Null disables offsite backups.
          '';
        };

        passwordFile = mkOption {
          type = types.nullOr types.path;
          default = null;
          description = "File with restic's encryption password. Keep a copy elsewhere: without it the backups can't be read.";
        };

        environmentFile = mkOption {
          type = types.nullOr types.path;
          default = null;
          description = "Credentials for the repository, e.g. AWS_ACCESS_KEY_ID=… and AWS_SECRET_ACCESS_KEY=… for S3.";
        };

        pruneOpts = mkOption {
          type = types.listOf types.str;
          default = ["--keep-daily 7" "--keep-weekly 4" "--keep-monthly 6"];
          description = "Which snapshots restic keeps.";
        };
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
    assertions = [
      {
        assertion = cfg.backup.offsite.repository == null || cfg.backup.offsite.passwordFile != null;
        message = "services.sorchat.backup.offsite needs a passwordFile to encrypt the backups with.";
      }
    ];

    # Offsite (restic): `restic-sorchat snapshots` lists them, `restic-sorchat restore latest
    # --target /tmp/restore` gets the newest back (the wrapper has the repository and keys set).
    services.restic.backups.sorchat = mkIf (cfg.backup.offsite.repository != null) {
      inherit (cfg.backup.offsite) repository passwordFile environmentFile pruneOpts;
      initialize = true;
      createWrapper = true;
      paths = [offsiteStaging];
      # A consistent snapshot first; restic then uploads only what changed since last time.
      backupPrepareCommand = "${snapshot} ${offsiteStaging}";
      backupCleanupCommand = "rm -rf ${offsiteStaging}";
      timerConfig = {
        OnCalendar = cfg.backup.schedule;
        Persistent = true;
        RandomizedDelaySec = "15m";
      };
    };

    # Restore (local): stop sorchat, copy a backup's sorchat.db and media/ to
    # /var/lib/private/sorchat/, start sorchat (systemd fixes the ownership).
    systemd.services.sorchat-backup = mkIf cfg.backup.enable {
      description = "Back up the sorchat database and media";
      path = [pkgs.coreutils pkgs.findutils];
      script = ''
        set -euo pipefail
        dest=${lib.escapeShellArg cfg.backup.directory}/$(date +%Y-%m-%d_%H%M%S)
        ${snapshot} "$dest"
        find ${lib.escapeShellArg cfg.backup.directory} -mindepth 1 -maxdepth 1 -type d -name '20*' \
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
        // lib.optionalAttrs (cfg.passkeys.androidCertFingerprints != []) {
          SORCHAT_PASSKEY_RP_ID = cfg.domain;
          SORCHAT_ANDROID_PACKAGE = cfg.passkeys.androidPackage;
          SORCHAT_ANDROID_CERT_SHA256 = lib.concatStringsSep "," cfg.passkeys.androidCertFingerprints;
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
