{ stdenv
, lib
, jre
, dockerTools
, toothpickServer
, fdbLib
, writeTextFile
, dumb-init
, docker-client
, prom2json
, curl
, jq
}:
let
  baseImage = dockerTools.pullImage {
    imageName = "public.ecr.aws/lts/ubuntu";
    imageDigest = "sha256:62b8f60c5c8e1717f460bb7af05e558b74feb8ac460ff2abbdd3a98becdc15ce";
    sha256 =
      if stdenv.isx86_64 then
        "sha256-tp9ionlqhxDIfM2LEp6aHcl00lvCxxgDtohY5zc2tIU=" else
        "sha256-SbQtlTrGf8hZePq6rX8J+5oa62lAWZRcxKJYPlzgCV4=";
  };
  javaSecurityOverrides = writeTextFile {
    name = "java.security.overrides";
    text = ''
      networkaddress.cache.ttl=5
      networkaddress.cache.negative.ttl=1
    '';
  };
  entrypoint = writeTextFile {
    name = "entrypoint";
    executable = true;
    text = ''
      #!/usr/bin/env bash
      exec dumb-init -- "${toothpickServer}"/bin/toothpick-server \
        -J-Djava.security.properties="${javaSecurityOverrides}" \
        -J-DFDB_LIBRARY_PATH_FDB_C="${fdbLib}"/libfdb_c.so \
        -J-DFDB_LIBRARY_PATH_FDB_JAVA="${fdbLib}"/libfdb_java.so \
        "$@"
    '';
  };
  prom2jq = writeTextFile {
    name = "prom2jq";
    executable = true;
    text = ''
      #!/usr/bin/env bash
      METRICS_URI=''${1:?"Metrics URI is required"}
      shift
      ${curl}/bin/curl -sf "''${METRICS_URI}" | ${prom2json}/bin/prom2json | ${jq}/bin/jq "$@"
    '';
  };
in
dockerTools.buildLayeredImage
{
  name = "toothpick-server";
  fromImage = baseImage;
  config = {
    Env = [
      "FDB_NETWORK_OPTION_EXTERNAL_CLIENT_DIRECTORY=${fdbLib}"
      "PATH=${lib.makeBinPath [ docker-client dumb-init jre prom2jq ]}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ];
    Entrypoint = [ entrypoint ];
  };
}
