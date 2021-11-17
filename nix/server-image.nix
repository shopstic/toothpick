{ fetchurl
, stdenv
, lib
, writeShellScriptBin
, dockerTools
, toothpickServer
, fdbLib
}:
let
  baseImage = dockerTools.pullImage {
    imageName = "openjdk";
    imageDigest = if stdenv.isx86_64 then "sha256:5352ab50afd260ab429b2debf82b3a5ba52b0785acff983f0f847798463871ab" else "sha256:f51998b423ae2adaa582ecd4d43d9e46bdc85d39683e8b9a6eea7ccba6815696";
    sha256 = if stdenv.isx86_64 then "sha256-S+rW9bPZ8cjAO34vlxnkymAFD16lzGMtvzkajmlJWfM=" else "sha256-TokAsNOmsuNN3R28rxUF8mR92TMrfYrKEgAbr8wwE28=";
  };
  entrypoint = writeShellScriptBin "toothpick-server" ''
    sed -i 's/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=5/g' /usr/local/openjdk-11/conf/security/java.security && \
    sed -i 's/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=1/g' /usr/local/openjdk-11/conf/security/java.security
    exec ${toothpickServer}/bin/toothpick-server
  '';
in
dockerTools.buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Env = [
      "LD_LIBRARY_PATH=${fdbLib}"
    ];
    Cmd = [ "${entrypoint}/bin/toothpick-server" ];
  };
}
