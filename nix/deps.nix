{ lib
, stdenv
, jdk
, sbt
, rsync
}:
stdenv.mkDerivation {
  pname = "toothpick-deps";
  version = import ./version.nix;

  src = builtins.path
    {
      path = ../.;
      name = "src";
      filter = (path: /* type */_:
        lib.hasInfix "/project" path ||
        lib.hasSuffix ".sbt" path ||
        lib.hasSuffix ".scalafmt.conf" path
      );
    };

  __noChroot = true;

  nativeBuildInputs = [ jdk sbt rsync ];

  phases = [ "unpackPhase" "installPhase" ];

  installPhase = ''
    mkdir -p $out/cache

    export XDG_CACHE_HOME="$out/cache"
    export PROTOC_CACHE="$XDG_CACHE_HOME/protoc_cache";
    export COURSIER_CACHE="$XDG_CACHE_HOME/coursier";

    export SBT_OPTS="-Dsbt.global.base=$XDG_CACHE_HOME/sbt -Dsbt.ivy.home=$XDG_CACHE_HOME/ivy"
    echo "SBT_OPTS=$SBT_OPTS"
  
    sed -i '/project.git/s/.*/project.git = false/' ./.scalafmt.conf

    mkdir -p ./toothpick-api/src/main/scala
    echo "object Dummy {}" > ./toothpick-api/src/main/scala/Dummy.scala

    sbt dependencyList cq protocExecutable compile

    find $out -type f -name "*.lock" -exec rm -f {} \;
  '';
}
