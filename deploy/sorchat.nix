# sorchat chat server behind the existing nginx, next to coturn.nix. Import from the VPS's
# NixOS configuration.
#
# Before deploying:
#   - build the server with `./gradlew :server:distTar` and copy
#     server/build/distributions/server.tar next to this file (redo this to update it)
#   - point an A (and AAAA) record for `domain` at the VPS
#   - put the Firebase service-account JSON at `fcmCredentialsFile` (sops-nix/agenix;
#     readable by root is enough: systemd hands it to the service)
{
  config,
  pkgs,
  ...
}: let
  domain = "chat.urbit.men"; # change me
  turnDomain = "turn.urbit.men";
  port = 8090; # only reachable through nginx
  fcmCredentialsFile = "/run/secrets/sorchat-fcm.json"; # change me
  # The same shared secret coturn uses, so the server can issue TURN credentials.
  turnSecretFile = config.services.coturn.static-auth-secret-file;

  server = pkgs.callPackage ./sorchat-server.nix {src = ./server.tar;};
in {
  systemd.services.sorchat = {
    description = "sorchat chat server";
    wantedBy = ["multi-user.target"];
    wants = ["network-online.target"];
    after = ["network-online.target"];

    environment = {
      HOST = "127.0.0.1";
      PORT = toString port;
      SORCHAT_DB = "/var/lib/sorchat/sorchat.db";
      SORCHAT_MEDIA_DIR = "/var/lib/sorchat/media";
      # %d is the directory where systemd puts the LoadCredential files below.
      SORCHAT_FCM_CREDENTIALS = "%d/fcm.json";
      SORCHAT_TURN_SECRET_FILE = "%d/turn-secret";
      SORCHAT_TURN_URLS = builtins.concatStringsSep "," [
        "stun:${turnDomain}:3478"
        "turn:${turnDomain}:3478?transport=udp"
        "turn:${turnDomain}:3478?transport=tcp"
        "turns:${turnDomain}:5349?transport=tcp"
      ];
    };

    serviceConfig = {
      ExecStart = "${server}/bin/sorchat-server";
      Restart = "on-failure";
      DynamicUser = true;
      StateDirectory = "sorchat";
      WorkingDirectory = "/var/lib/sorchat";
      LoadCredential = [
        "fcm.json:${fcmCredentialsFile}"
        "turn-secret:${turnSecretFile}"
      ];

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

  services.nginx.virtualHosts.${domain} = {
    enableACME = true;
    forceSSL = true;
    locations."/" = {
      proxyPass = "http://127.0.0.1:${toString port}";
      proxyWebsockets = true;
      extraConfig = ''
        # Voice notes up to 16 MB.
        client_max_body_size 17m;
        # The WebSocket is pinged every 30 s; don't cut it off in between.
        proxy_read_timeout 120s;
      '';
    };
  };
}
