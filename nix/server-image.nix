{ lib
, jre
, dockerTools
, toothpickServer
, fdbLib
, writeTextFile
, dumb-init
, docker-client
}:
let
  baseImage = dockerTools.pullImage {
    imageName = "docker.io/library/ubuntu";
    imageDigest = "sha256:93a94c12448f393522f44d8a1b34936b7f76890adea34b80b87a245524d1d574";
    sha256 = "sha256-rAy5pQ3wrBSNzTqwX8WTEfTqVmILPGqQg/j69v2C4EM=";
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

      exec dumb-init -- ${toothpickServer}/bin/toothpick-server \
        -J-Djava.security.properties=${javaSecurityOverrides} \
        -J-DFDB_LIBRARY_PATH_FDB_C=${fdbLib}/libfdb_c.so \
        -J-DFDB_LIBRARY_PATH_FDB_JAVA=${fdbLib}/libfdb_java.so \
        "$@"
    '';
  };
in
dockerTools.buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Env = [
      "PATH=${lib.makeBinPath [ docker-client dumb-init jre ]}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ];
    Entrypoint = [ entrypoint ];
  };
}
