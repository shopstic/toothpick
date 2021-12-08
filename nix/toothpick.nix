{ lib
, stdenv
, jdk
, sbt
, rsync
, makeWrapper
, toothpickDeps
}:
stdenv.mkDerivation {
  pname = "toothpick";
  version = import ./version.nix;

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
    cp -R "${toothpickDeps}/cache" "$TMPDIR/"
    ls -la "$TMPDIR/cache"

    export XDG_CACHE_HOME="$TMPDIR/cache"
    chmod -R +w "$XDG_CACHE_HOME"
    
    export PROTOC_CACHE="$XDG_CACHE_HOME/protoc_cache";
    export COURSIER_CACHE="$XDG_CACHE_HOME/coursier";

    export SBT_OPTS="-Dsbt.global.base=$XDG_CACHE_HOME/sbt -Dsbt.ivy.home=$XDG_CACHE_HOME/ivy -Xmx4g -Xss6m -XX:-UseContainerSupport"
    echo "SBT_OPTS=$SBT_OPTS"

    sbt --client cq < <(echo q)
    sbt --client 'set server / dockerApiVersion := Some(com.typesafe.sbt.packager.docker.DockerApiVersion(1, 41))'
    sbt --client 'set server / dockerVersion := com.typesafe.sbt.packager.docker.DockerVersion.parse("20.10.10")'
  '';

  buildPhase = ''
    sbt --client compile
    sbt --client Test / compile
  '';

  checkPhase = ''
    sbt --client test
  '';

  installPhase = ''
    sbt --client runner / stage
    mkdir -p $out
    rsync -avrx --exclude '*.bat' ./toothpick-runner/target/universal/stage/ $out/

    sbt --client server / stage
    mkdir -p $server
    rsync -avrx --exclude '*.bat' ./toothpick-server/target/universal/stage/ $server/

    sbt --client server / Docker / stage
    mkdir -p $dockerServer
    rsync -avrx --exclude '*.bat' ./toothpick-server/target/docker/stage/ $dockerServer/

    sbt --client shutdown
  '';

  doCheck = true;
  dontStrip = true;
  dontPatch = true;
  dontFixup = true;
  # dontPatchShebangs = true;

  outputs = [ "out" "server" "dockerServer" ];

  meta = with lib;
    {
      description = "Toothpick";
      homepage = "https://toothpick.dev";
      license = licenses.asl20;
      platforms = [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" ];
    };
}
