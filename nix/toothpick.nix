{ lib
, stdenv
, jdk
, sbt
, rsync
, runCommand
, makeWrapper
, toothpick-deps
}:
let
  name = "toothpick";
  version = import ./version.nix;

  result = stdenv.mkDerivation {
    pname = name;
    inherit version;
    src = builtins.path
      {
        path = ../.;
        name = "src";
        filter = (path: /* type */_:
          lib.hasInfix "/toothpick-" path ||
          lib.hasInfix "/project" path ||
          lib.hasSuffix ".sbt" path ||
          lib.hasSuffix ".scalafmt.conf" path
        );
      };

    buildInputs = [
      jdk
      sbt
      rsync
      makeWrapper
    ];

    configurePhase = ''
      cp -R "${toothpick-deps}/cache" "$TMPDIR/"
      ls -la "$TMPDIR/cache"

      export XDG_CACHE_HOME="$TMPDIR/cache"
      chmod -R +w "$XDG_CACHE_HOME"
    
      export PROTOC_CACHE="$XDG_CACHE_HOME/protoc_cache";
      export COURSIER_CACHE="$XDG_CACHE_HOME/coursier";

      export SBT_OPTS="-Dsbt.global.base=$XDG_CACHE_HOME/sbt -Dsbt.ivy.home=$XDG_CACHE_HOME/ivy -Xmx4g -Xss6m"
      echo "SBT_OPTS=$SBT_OPTS"

      trap "sbt --java-client shutdown" EXIT

      sed -i '/project.git/s/.*/project.git = false/' ./.scalafmt.conf

      sbt --java-client "$(cat << EOF
;cq
;set server / dockerApiVersion := Some(com.typesafe.sbt.packager.docker.DockerApiVersion(1, 41))
;set server / dockerVersion := com.typesafe.sbt.packager.docker.DockerVersion.parse("20.10.10")
EOF
)"
    '';

    buildPhase = ''
      sbt --java-client ";compile;Test / compile"
    '';

    checkPhase = ''
      sbt --java-client test
    '';

    installPhase = ''
      sbt --java-client ";runner / stage;server / Docker / stage"
      
      mkdir -p $out
      rsync -avrx --exclude '*.bat' ./toothpick-runner/target/universal/stage/ $out/

      mkdir -p $server
      rsync -avrx --exclude '*.bat' ./toothpick-server/target/docker/stage/4/opt/docker/ $server/
      rsync -avrx --exclude '*.bat' ./toothpick-server/target/docker/stage/2/opt/docker/ $serverDeps/
      patchShebangs $server/bin/toothpick-server

      sed -i '/declare -r app_classpath/s/.*/declare -r app_classpath="''${TOOTHPICK_SERVER_APP_LIB_DIR:-$lib_dir}\/*"/' $server/bin/toothpick-server
    '';

    doCheck = true;
    dontStrip = true;
    dontPatch = true;
    dontFixup = true;
    # dontPatchShebangs = true;

    outputs = [ "out" "server" "serverDeps" ];

    meta = with lib;
      {
        description = "Toothpick";
        homepage = "https://toothpick.dev";
        license = licenses.asl20;
        platforms = [ "x86_64-linux" "aarch64-darwin" "aarch64-linux" ];
      };
  };
in
result
