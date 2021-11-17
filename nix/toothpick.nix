{ lib
, stdenv
, mkShellNoCC
, jdk
, sbt
, rsync
, makeWrapper
}:
stdenv.mkDerivation {
  pname = "toothpick";
  version = import ./version.nix;

  src = builtins.path
    {
      path = ../.;
      name = "src";
      filter = (path: type:
        lib.hasInfix "/toothpick-" path ||
        lib.hasInfix "/project" path ||
        lib.hasSuffix ".sbt" path ||
        lib.hasSuffix ".scalafmt.conf" path ||
        lib.hasSuffix ".env" path
      );
    };

  __noChroot = true;

  buildInputs = [
    jdk
    sbt
    rsync
    makeWrapper
  ];

  configurePhase = ''
    export PROTOC_CACHE="$TMPDIR/protoc_cache";
    export COURSIER_CACHE="$TMPDIR/coursier";
    _SBT_OPTS=(
      "-Dsbt.global.base=$TMPDIR/sbt"
      "-Dsbt.ivy.home=$TMPDIR/ivy"
      "--illegal-access=deny"
      "--add-opens"
      "java.base/java.util=ALL-UNNAMED"
      "--add-opens"
      "java.base/java.lang=ALL-UNNAMED"
      "-Xmx4g"
      "-Xss6m"
    );

    export SBT_OPTS="''${_SBT_OPTS[*]}"

    echo "PROTOC_CACHE=$PROTOC_CACHE"
    echo "COURSIER_CACHE=$COURSIER_CACHE"
    echo "SBT_OPTS=$SBT_OPTS"

    source ./.env

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
