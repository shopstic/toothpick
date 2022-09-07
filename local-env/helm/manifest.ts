import { RemoteChartConfigMap, RemoteChartSource } from "./deps/helmet.ts";

const remoteCharts: RemoteChartConfigMap = {
  "env-injector": {
    source: RemoteChartSource.OciRegistry,
    ociRef: "oci://public.ecr.aws/shopstic/charts/env-injector",
    version: "1.4.3",
  },
};

export default remoteCharts;
