# TURN/STUN relay for sorchat calls. Import from the VPS's NixOS configuration.
#
# Before deploying:
#   - point an A (and AAAA) record for `domain` at the VPS
#   - generate the shared secret: `openssl rand -hex 32`, and put it at `secretFile`
#     (with sops-nix/agenix, readable by the `turnserver` user). The chat server
#     gets the same secret to mint short-lived TURN credentials.
{config, ...}: let
  domain = "turn.example.com"; # change me
  secretFile = "/run/secrets/coturn-auth-secret"; # change me
  certDir = config.security.acme.certs.${domain}.directory;
in {
  services.coturn = {
    enable = true;
    realm = domain;
    # Time-limited credentials derived from the shared secret (TURN REST API scheme).
    use-auth-secret = true;
    static-auth-secret-file = secretFile;

    listening-port = 3478; # STUN/TURN over UDP and TCP
    tls-listening-port = 5349; # TURN over TLS, for networks that block UDP
    min-port = 49152; # relay ports
    max-port = 65535;
    cert = "${certDir}/full.pem";
    pkey = "${certDir}/key.pem";

    no-cli = true;
    no-tcp-relay = true; # WebRTC media is UDP

    extraConfig = ''
      fingerprint
      no-multicast-peers
      # Never relay into private/internal networks (keeps the relay from being used to reach the VPS's LAN).
      denied-peer-ip=0.0.0.0-0.255.255.255
      denied-peer-ip=10.0.0.0-10.255.255.255
      denied-peer-ip=100.64.0.0-100.127.255.255
      denied-peer-ip=127.0.0.0-127.255.255.255
      denied-peer-ip=169.254.0.0-169.254.255.255
      denied-peer-ip=172.16.0.0-172.31.255.255
      denied-peer-ip=192.168.0.0-192.168.255.255
      denied-peer-ip=::1
      denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
      denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
      # Limits per user and in total, so a leaked credential can't eat all bandwidth.
      user-quota=12
      total-quota=1200

      # Only needed if the VPS sits behind 1:1 NAT (AWS, GCP, Oracle...):
      # external-ip=<public-ip>/<private-ip>
    '';
  };

  # Let's Encrypt certificate, with the HTTP-01 challenge served by the existing nginx.
  # The vhost only answers the challenge; nginx doesn't use the certificate itself.
  services.nginx.virtualHosts.${domain}.enableACME = true;
  security.acme = {
    acceptTerms = true;
    defaults.email = "you@example.com"; # change me (may already be set elsewhere)
    certs.${domain}.reloadServices = ["coturn.service"];
  };
  # The certificate belongs to the nginx group; coturn (user `turnserver`) needs to read it too.
  users.users.turnserver.extraGroups = ["nginx"];

  networking.firewall = {
    allowedTCPPorts = [3478 5349]; # plus 80 for ACME, which the nginx setup presumably opens already
    allowedUDPPorts = [3478 5349];
    allowedUDPPortRanges = [
      {
        from = 49152;
        to = 65535;
      }
    ];
  };
}
