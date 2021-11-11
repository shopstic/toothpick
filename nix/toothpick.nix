{ gitignoreSource
, lib
, stdenv
, mkShellNoCC
, jdk
, sbt
, rsync
, makeWrapper
}:
let
  buildInputs = [
    jdk
    sbt
    rsync
    makeWrapper
  ];
in
{
  shell = mkShellNoCC
    {
      buildInputs = buildInputs;
    };

  package = stdenv.mkDerivation {
    pname = "toothpick";
    version = import ./version.nix;

    src = builtins.filterSource (path: type: builtins.trace path (!lib.hasSuffix ".nix" path)) (gitignoreSource ../.);

    __noChroot = true;

    buildInputs = buildInputs;

    configurePhase = ''
      ls -la ./nix
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

      sbt --client cq
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
      # cp ./scripts/runner/runner.sh $out/bin/
      # mkdir -p "$out/jre/bin"

      # find "''${jre}" -mindepth 1 -maxdepth 1 -not -path "*/bin" -print0 | xargs -0 -I{} ln -s "{}" "$out/jre/"
      # find "''${jre}/bin" -mindepth 1 -maxdepth 1 -not -path "*/java" -print0 | xargs -0 -I{} ln -s "{}" "$out/jre/bin/"
      # ln -s "$out/runner/bin/runner.sh" "$out/jre/bin/java"

      sbt --client server / stage
      mkdir -p $server
      rsync -avrx --exclude '*.bat' ./toothpick-server/target/universal/stage/ $server/

      sbt --client shutdown
    '';

    doCheck = true;
    dontStrip = true;
    dontPatch = true;
    dontFixup = true;
    # dontPatchShebangs = true;

    outputs = [ "out" "server" ];

    meta = with lib;
      {
        description = "Toothpick";
        homepage = "https://toothpick.dev";
        license = licenses.asl20;
        platforms = [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" ];
      };
  };
}
