import { LocalK8sConfig } from "../helm/deps/jetski.ts";

const config: LocalK8sConfig = {
  name: "toothpick-local",
  image: "focal",
  cpus: 2,
  memoryGiBs: 8,
  diskGiBs: 50,
  k3sVersion: "v1.23.10+k3s1",
  clusterCidr: "172.31.240.0/24",
  serviceCidr: "172.31.241.0/24",
  clusterDnsIp: "172.31.241.10",
  clusterDomain: "toothpick.local",
  disableComponents: {
    traefik: true,
    metricsServer: true,
  },
  nodeLabels: {
    "helmet.run/workload": "general",
    "topology.kubernetes.io/zone": "local",
  },
  sshDirectoryPath: "./private/.ssh",
};

export default config;
