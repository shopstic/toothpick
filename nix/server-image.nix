{ stdenv
, lib
, buildEnv
, writeTextFile
, writeShellScript
, writeShellScriptBin
, runCommand
, nix2container
, jre
, toothpick
, fdbLib
, dumb-init
, docker-client
, prom2json
, curl
, bash
, coreutils
, jq
, gawk
, gnugrep
}:
let
  name = "toothpick-server";
  # base-image = nix2container.pullImage {
  #   imageName = "docker.io/library/ubuntu";
  #   imageDigest = "sha256:34fea4f31bf187bc915536831fd0afc9d214755bf700b5cdb1336c82516d154e";
  #   sha256 =
  #     if stdenv.isx86_64 then
  #       "sha256-js71udw5wijNGx+U7xekS3y9tUvFAdJqQMoA9lOTpr8=" else
  #       "sha256-jkkPmXnYVU0LB+KQv35oCe5kKs6KWDEDmTXw4/yx8nU=";
  # };

  javaSecurityOverrides = writeTextFile {
    name = "java.security.overrides";
    text = ''
      networkaddress.cache.ttl=5
      networkaddress.cache.negative.ttl=1
    '';
  };

  entrypoint = writeShellScript "entrypoint" ''
    exec dumb-init -- toothpick-server \
      -J-Djava.security.properties="${javaSecurityOverrides}" \
      -J-DFDB_LIBRARY_PATH_FDB_C="${fdbLib}"/libfdb_c.so \
      -J-DFDB_LIBRARY_PATH_FDB_JAVA="${fdbLib}"/libfdb_java.so \
      "$@"
  '';

  prom2jq = writeShellScriptBin "prom2jq" ''
    METRICS_URI=''${1:?"Metrics URI is required"}
    ${curl}/bin/curl -sf "''${METRICS_URI}" | ${prom2json}/bin/prom2json | ${jq}/bin/jq "''${@:2}"
  '';

  app = buildEnv {
    name = "app";
    pathsToLink = [ "/bin" "/lib" ];
    paths = [
      toothpick.server
      toothpick.serverDeps
    ];
  };

  nix-bin = buildEnv {
    name = "nix-bin";
    pathsToLink = [ "/bin" ];
    paths = [
      docker-client
      dumb-init
      jre
      prom2jq
      bash
      coreutils
      gawk
      gnugrep
    ];
  };
  image =
    nix2container.buildImage
      {
        inherit name;
        tag = toothpick.version;
        # fromImage = base-image;
        copyToRoot = [ nix-bin app ];
        maxLayers = 80;
        config = {
          volumes = {
            "/tmp" = { };
          };
          env = [
            "PATH=/bin"
            "TOOTHPICK_SERVER_APP_LIB_DIR=/lib"
            "FDB_NETWORK_OPTION_EXTERNAL_CLIENT_DIRECTORY=${fdbLib}"
          ];
          entrypoint = [ entrypoint ];
        };
      };
in
image // {
  dir = runCommand "${name}-dir" { } "${image.copyTo}/bin/copy-to dir:$out";
}
