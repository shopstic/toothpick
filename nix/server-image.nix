{ stdenv
, dockerTools
, toothpickServer
, fdbLib
, writeTextDir
, writeTextFile
, buildahBuild
, dumb-init
}:
let
  baseImage = buildahBuild
    {
      name = "server-base-image";
      context = writeTextDir "Dockerfile" ''
        FROM docker.io/library/openjdk:11.0.13-slim-bullseye@sha256:29eca747201257182d746a59ebc96751436ccec372274c0cf22229b44ea0073c

        RUN \
          sed -i 's/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=5/g' /usr/local/openjdk-11/conf/security/java.security && \
          sed -i 's/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=1/g' /usr/local/openjdk-11/conf/security/java.security
      '';
      squash = false;
      outputHash =
        if stdenv.isx86_64 then
          "sha256-TW+h2MclCJjcmpTUZ/uE6hkThzf/OpEN54gICmZIYC8=" else
          "sha256-Ka1hIxC3rYWaX1INy7VuKomNkSO58QphSyCIJriIQts=";
    };
  entrypoint = writeTextFile {
    name = "entrypoint";
    executable = true;
    text = ''
      #!/usr/bin/env bash
      set -euo pipefail
      export LD_LIBRARY_PATH="${fdbLib}:$LD_LIBRARY_PATH"
      exec ${dumb-init}/bin/dumb-init -- ${toothpickServer}/bin/toothpick-server "$@"
    '';
  };
in
dockerTools.buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Entrypoint = [ entrypoint ];
  };
}
