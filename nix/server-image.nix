{ stdenv
, dockerTools
, toothpickServer
, fdbLib
, writeText
, buildahBuild
, dumb-init
}:
let
  baseImageDigest =
    if stdenv.isx86_64 then
      "sha256:5352ab50afd260ab429b2debf82b3a5ba52b0785acff983f0f847798463871ab" else
      "sha256:f51998b423ae2adaa582ecd4d43d9e46bdc85d39683e8b9a6eea7ccba6815696";

  baseImage = buildahBuild
    {
      dockerFile = writeText "Dockerfile" ''
        FROM docker.io/library/openjdk@${baseImageDigest}

        RUN \
          sed -i 's/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=5/g' /usr/local/openjdk-11/conf/security/java.security && \
          sed -i 's/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=1/g' /usr/local/openjdk-11/conf/security/java.security
      '';
      outputHash =
        if stdenv.isx86_64 then
          "sha256-4fABHGeG2i7IeSftH3UkRcR6UlQ8j+hP4QVC4HEt9Ao=" else
          "sha256-RYD1EP830CoiD/uPrOB6WJNwyeSp4pHdgqtzULmaF5o=";
    };
in
dockerTools.buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Env = [
      "LD_LIBRARY_PATH=${fdbLib}"
    ];
    Cmd = [ "${dumb-init}/bin/dumb-init" "--" "${toothpickServer}/bin/toothpick-server" ];
  };
}
