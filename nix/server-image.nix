{ fetchurl
, stdenv
, lib
, makeWrapper
, writeText
, pullImage
, buildLayeredImage
, toothpickServer
}:
let
  fdbBindings = fetchurl {
    url = "https://www.foundationdb.org/downloads/6.3.22/linux/libfdb_c_6.3.22.so";
    sha256 = "19ai87m416ff1gz7rglpdmhd5539skhlagk4y0bwn56qkl2nmxva";
  };
  baseImage = pullImage {
    imageName = "eclipse-temurin";
    imageDigest = "sha256:c2993cc66158bab7c8682f85cb7f3ca64c4b6d29fc2305f6aa640a5423b6524a";
    sha256 = "sha256-vcuCk29tN/EIAoUfHQVYO84CpmoaDGUaDlpRtFXOilc=";
  };
in
buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Env = [
      "LD_LIBRARY_PATH=${fdbBindings}"
    ];
    Cmd = [ "${toothpickServer}/bin/toothpick-server" ];
  };
}
