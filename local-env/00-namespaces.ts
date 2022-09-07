import { createK8sNamespace, defineBundle } from "./helm/deps/helmet.ts";
import { createResourceGroup } from "./helm/deps/resource-group.ts";

export const namespace = "default";
export const controllersNamespace = "drivers";
export const devNamespace = "dev";

export default defineBundle({
  releaseId: "toothpick-local-namespaces",
  releaseNamespace: namespace,
  create() {
    return Promise.all([
      createResourceGroup({
        name: "namespaces",
        namespace,
        resources: [
          controllersNamespace,
          devNamespace,
        ].map((name) =>
          createK8sNamespace({
            metadata: {
              name,
            },
          })
        ),
      }),
    ]);
  },
});
