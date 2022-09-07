import { defineBundle, K8sPvc } from "./helm/deps/helmet.ts";
import { devNamespace as namespace } from "./00-namespaces.ts";
import {
  createFdbClusterResources,
  createFdbStatefulPersistentVolumeClaims,
  FdbStatefulConfig,
} from "./helm/deps/fdb.ts";
import { createResourceGroup, extractK8sResources } from "./helm/deps/resource-group.ts";
import { envInjectionLabelName, envInjectionLabelValue } from "./01-controllers.ts";

export default defineBundle({
  releaseId: "toothpick-local-dev",
  releaseNamespace: namespace,
  async create() {
    const fdbNodes = ["toothpick-local"];
    const fdbProcessClasses: FdbStatefulConfig["processClass"][] = [
      "coordinator",
      "storage",
      "log",
    ];

    const storageClassName = "local-path";

    const fdbStatefulConfigs: Record<string, FdbStatefulConfig> = Object
      .fromEntries(
        fdbProcessClasses.flatMap((processClass) => {
          return fdbNodes.map((node) => {
            const cfg: FdbStatefulConfig = {
              processClass,
              servers: [{ port: 4500 }],
              nodeSelector: {
                "kubernetes.io/hostname": node,
              },
              volumeSize: processClass === "storage" ? "10Gi" : "1Gi",
              storageClassName,
            };

            return [`${processClass}-${node}`, cfg];
          });
        }),
      );

    const fdbPvcs: K8sPvc[] = createFdbStatefulPersistentVolumeClaims({
      baseName: "dev-fdb",
      baseLabels: {
        "app.kubernetes.io/name": "dev-fdb",
        "app.kubernetes.io/instance": "dev-fdb",
      },
      configs: fdbStatefulConfigs,
    });

    const fdbClusterResources = createFdbClusterResources({
      baseName: "dev-fdb",
      namespace,
      storageEngine: "ssd-redwood-1-experimental",
      redundancyMode: "single",
      storageMigrationType: "aggressive",
      stateless: {
        mode: "dev",
        count: 1,
      },
      stateful: fdbStatefulConfigs,
      createServiceMonitor: false,
      labels: {
        [envInjectionLabelName]: envInjectionLabelValue,
      },
    });

    return await Promise.all([
      createResourceGroup({
        name: "fdb-cluster",
        namespace,
        resources: [
          ...fdbPvcs,
          ...extractK8sResources(fdbClusterResources),
        ],
      }),
    ]);
  },
});
