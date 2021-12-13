{ stdenv, lib, fetchzip }:
stdenv.mkDerivation rec {
  pname = "intellij-scala-runners";
  version = "2021.3.8";

  src = fetchzip {
    name = "${pname}-${version}";
    url = "https://plugins.jetbrains.com/files/1347/143428/scala-intellij-bin-${version}.zip";
    sha256 = "1sdlm4zhhdjv9v0bx2afi61d4zvh1ngdz0935bsmbdi31rkan16z";
  };

  installPhase = ''
    mkdir -p $out
    mv ./lib/runners.jar $out/intellij-scala-runners.jar
  '';

  meta = with lib; {
    homepage = "https://plugins.jetbrains.com/plugin/1347-scala";
    license = licenses.asl20;
    description = "Intellij Scala Plugin runner";
    platforms = platforms.all;
  };
}
