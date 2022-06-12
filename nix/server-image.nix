{ stdenv
, lib
, jre
, dockerTools
, toothpickServer
, fdbLib
, writeTextFile
, dumb-init
, docker-client
, prom2json
, curl
, jq
}:
let
  baseImage = dockerTools.pullImage {
    imageName = "docker.io/library/ubuntu";
    imageDigest = "sha256:93a94c12448f393522f44d8a1b34936b7f76890adea34b80b87a245524d1d574";
    sha256 =
      if stdenv.isx86_64 then
        "sha256-s+eiBV7iA29UxnWg9rI2oMpwH/CNc9QRPbOUJJyBoTk=" else
        "sha256-rAy5pQ3wrBSNzTqwX8WTEfTqVmILPGqQg/j69v2C4EM=";
  };
  javaSecurityOverrides = writeTextFile {
    name = "java.security.overrides";
    text = ''
      networkaddress.cache.ttl=5
      networkaddress.cache.negative.ttl=1
    '';
  };
  entrypoint = writeTextFile {
    name = "entrypoint";
    executable = true;
    text = ''
      #!/usr/bin/env bash
      exec dumb-init -- "${toothpickServer}"/bin/toothpick-server \
        -J-Djava.security.properties="${javaSecurityOverrides}" \
        -J-DFDB_LIBRARY_PATH_FDB_C="${fdbLib}"/libfdb_c.so \
        -J-DFDB_LIBRARY_PATH_FDB_JAVA="${fdbLib}"/libfdb_java.so \
        "$@"
    '';
  };
  prom2jq = writeTextFile {
    name = "prom2jq";
    executable = true;
    text = ''
      #!/usr/bin/env bash
      METRICS_URI=''${1:?"Metrics URI is required"}
      shift
      ${curl}/bin/curl -sf "''${METRICS_URI}" | ${prom2json}/bin/prom2json | ${jq}/bin/jq "$@"
    '';
  };
in
dockerTools.buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Env = [
      "PATH=${lib.makeBinPath [ docker-client dumb-init jre prom2jq ]}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ];
    Entrypoint = [ entrypoint ];
  };
}
