{ stdenv, lib, fetchzip }:
stdenv.mkDerivation rec {
  pname = "intellij-scala-runners";
  version = "2024.1.15";

  src = fetchzip {
    name = "${pname}-${version}";
    url = "https://plugins.jetbrains.com/files/1347/516275/scala-intellij-bin-${version}.zip";
    sha256 = "sha256-2g1pKR5F/K2KPzdTZolYp58REgLvbf606OkEB4l03L4=";
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
