{ stdenv
, lib
, buildEnv
, writeTextFile
, writeShellScript
, writeShellScriptBin
, runCommand
, nonRootShadowSetup
, nix2container
, jre
, toothpick
, fdbLib
, dumb-init
, prom2json
, grpc-health-probe
, curl
, jq
, bash
}:
let
  name = "toothpick-server";

  base-image = nix2container.pullImage {
    imageName = "docker.io/docker";
    imageDigest = "sha256:79f5cf744ab66c48ff532b8dea2662dc90db30faded68ff7b33ce7109578ca7d";
    sha256 =
      if stdenv.isx86_64 then
        "sha256-Rg89olBJl5M3xxFBL51SbP3KPWZEH6uQjEfuOZOPK50=" else
        "sha256-4W4jG5ehcMvCG2vpqDNUSAEoU9/v2NsWYewPw44JYgo=";
  };

  user = "app";
  shadow = nonRootShadowSetup { inherit user; uid = 1001; shellBin = "${bash}/bin/bash"; };
  home-dir = runCommand "home-dir" { } ''mkdir -p $out/home/${user}'';

  javaSecurityOverrides = writeTextFile {
    name = "java.security.overrides";
    text = ''
      networkaddress.cache.ttl=5
      networkaddress.cache.negative.ttl=1
    '';
  };

  entrypoint = writeShellScriptBin "entrypoint.sh" ''
    toothpick-server \
      -J-Djava.security.properties="${javaSecurityOverrides}" \
      -J-DFDB_LIBRARY_PATH_FDB_C="${fdbLib}"/libfdb_c.so \
      -J-DFDB_LIBRARY_PATH_FDB_JAVA="${fdbLib}"/libfdb_java.so \
      "$@"
  '';

  prom2jq = writeShellScriptBin "prom2jq" ''
    METRICS_URI=''${1:?"Metrics URI is required"}
    curl -sf "''${METRICS_URI}" | prom2json | jq "''${@:2}"
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
    postBuild = ''
      mv $out/bin $out/nix-bin
    '';
    paths = [
      dumb-init
      bash
      curl
      prom2jq
      prom2json
      jq
      jre
      grpc-health-probe
      entrypoint
    ];
  };

  image =
    nix2container.buildImage
      {
        inherit name;
        tag = toothpick.version;
        fromImage = base-image;
        copyToRoot = [ nix-bin shadow home-dir app ];
        maxLayers = 80;
        perms = [
          {
            path = home-dir;
            regex = "/home/${user}$";
            mode = "0777";
          }
        ];
        config = {
          volumes = {
            "/tmp" = { };
          };
          env = [
            "PATH=/nix-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            "TOOTHPICK_SERVER_APP_LIB_DIR=/lib"
            "FDB_NETWORK_OPTION_EXTERNAL_CLIENT_DIRECTORY=${fdbLib}"
          ];
          entrypoint = [ "dumb-init" "--" "entrypoint.sh" ];
        };
      };
in
image // {
  dir = runCommand "${name}-dir" { } "${image.copyTo}/bin/copy-to dir:$out";
}
