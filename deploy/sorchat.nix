# NixOS module for the sorchat chat server, behind nginx, next to coturn.nix.
#
#   imports = [ ./sorchat.nix ];
#   services.sorchat = {
#     enable = true;
#     package = pkgs.callPackage ./sorchat-server.nix { src = ./server.tar; };
#     domain = "chat.urbit.men";
#     fcmCredentialsFile = "/run/secrets/sorchat-fcm.json";
#   };
#
# The package is built from `./gradlew :server:distTar` (server/build/distributions/server.tar).
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
      description = "The sorchat server package, see sorchat-server.nix.";
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
