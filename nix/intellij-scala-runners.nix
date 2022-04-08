{ stdenv, lib, fetchzip }:
stdenv.mkDerivation rec {
  pname = "intellij-scala-runners";
  version = "2022.1.11";

  src = fetchzip {
    name = "${pname}-${version}";
    url = "https://plugins.jetbrains.com/files/1347/166693/scala-intellij-bin-${version}.zip";
    sha256 = "sha256-ExlENrcITS0LJJSu4H+d0JxunW5zdi285mVPZhCjbEw=";
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
