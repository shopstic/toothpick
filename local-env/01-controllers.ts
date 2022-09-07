import { defineBundle } from "./helm/deps/helmet.ts";
import { controllersNamespace as namespace } from "./00-namespaces.ts";
import { compileChartInstance } from "./helm/deps/helmet.ts";
import { createEnvInjector } from "./helm/types/env-injector.ts";

export const envInjectionLabelName = "helmet.run/inject-node-labels-as-env";
export const envInjectionLabelValue = "yes";

export default defineBundle({
  releaseId: "toothpick-local-controllers",
  releaseNamespace: namespace,
  async create() {
    const envInjectorPromise = compileChartInstance(createEnvInjector({
      namespace,
      values: {
        mutationWebhook: {
          objectSelector: {
            matchExpressions: [
              {
                key: envInjectionLabelName,
                operator: "In",
                values: [envInjectionLabelValue],
              },
            ],
          },
        },
      },
    }));

    return await Promise.all([
      envInjectorPromise,
    ]);
  },
});
