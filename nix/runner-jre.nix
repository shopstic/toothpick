{ stdenv
, lib
, makeWrapper
, toothpickRunner
, jre
}:
stdenv.mkDerivation
{
  pname = "toothpick-runner-jre";
  version = import ./version.nix;
  src = ../scripts/runner;
  dontStrip = true;
  dontPatch = true;
  dontFixup = true;
  nativeBuildInputs = [ makeWrapper ];
  installPhase = ''
    mkdir -p $out/bin

    cp ./runner.sh $out/bin/
    makeWrapper ${toothpickRunner}/bin/toothpick-runner $out/bin/toothpick-runner \
      --prefix PATH : "${lib.makeBinPath [ jre ]}"
    
    find "${jre}" -mindepth 1 -maxdepth 1 -not -path "*/bin" -print0 | xargs -0 -I{} ln -s "{}" "$out/"
    find "${jre}/bin" -mindepth 1 -maxdepth 1 -not -path "*/java" -print0 | xargs -0 -I{} ln -s "{}" "$out/bin/"
    ln -s "$out/bin/runner.sh" "$out/bin/java"
  '';
}
