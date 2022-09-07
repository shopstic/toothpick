// deno-lint-ignore-file
// DO NOT MODIFY: This file was generated via "helmet typeify ..."
import { K8sImagePullPolicy } from "../deps/helmet.ts";
import { IoK8sApiCoreV1LocalObjectReference } from "https://deno.land/x/k8s@1.22/models/IoK8sApiCoreV1LocalObjectReference.ts";
import { IoK8sApimachineryPkgApisMetaV1ObjectMeta } from "https://deno.land/x/k8s@1.22/models/IoK8sApimachineryPkgApisMetaV1ObjectMeta.ts";
import { IoK8sApiCoreV1SecurityContext } from "https://deno.land/x/k8s@1.22/models/IoK8sApiCoreV1SecurityContext.ts";
import { IoK8sApiCoreV1ResourceRequirements } from "https://deno.land/x/k8s@1.22/models/IoK8sApiCoreV1ResourceRequirements.ts";
import { IoK8sApiCoreV1PodSpec } from "https://deno.land/x/k8s@1.22/models/IoK8sApiCoreV1PodSpec.ts";
import {
  basename,
  ChartInstanceConfig,
  dirname,
  extname,
  fromFileUrl,
  joinPath,
  K8sResource,
} from "../deps/helmet.ts";

export interface EnvInjectorChartValues {
  "replicaCount"?: number; /* 1 */
  "image"?: {
    "repository"?: string; /* "public.ecr.aws/shopstic/k8s-env-injector" */
    "pullPolicy"?: K8sImagePullPolicy; /* "IfNotPresent" */
    "tag"?: string; /* "" */
  };
  "imagePullSecrets"?: Array<IoK8sApiCoreV1LocalObjectReference>; /* [] */
  "nameOverride"?: string; /* "" */
  "fullnameOverride"?: string; /* "" */
  "serviceAccount"?: {
    "create"?: boolean; /* true */
    "annotations"?:
      IoK8sApimachineryPkgApisMetaV1ObjectMeta["annotations"]; /* {} */
    "name"?: string; /* "" */
  };
  "podAnnotations"?:
    IoK8sApimachineryPkgApisMetaV1ObjectMeta["annotations"]; /* {} */
  "podSecurityContext"?: IoK8sApiCoreV1SecurityContext; /* {} */
  "securityContext"?: IoK8sApiCoreV1SecurityContext; /* {} */
  "service"?: { "type"?: string; /* "ClusterIP" */ "port"?: number /* 443 */ };
  "mutationWebhook"?: {
    "defaultConfigMapPrefix"?: string; /* "pod-" */
    "namespaceSelector"?: any; /* null */
    "objectSelector"?: {
      "matchExpressions"?: any[]; /* [] */
      "matchLabels"?: {};
    };
  };
  "resources"?: IoK8sApiCoreV1ResourceRequirements; /* {} */
  "nodeSelector"?: IoK8sApiCoreV1PodSpec["nodeSelector"]; /* {} */
  "tolerations"?: IoK8sApiCoreV1PodSpec["tolerations"]; /* [] */
  "affinity"?: IoK8sApiCoreV1PodSpec["affinity"]; /* {} */
}

export type EnvInjectorChartInstanceConfig = ChartInstanceConfig<
  EnvInjectorChartValues
>;

export const defaultName = basename(import.meta.url, extname(import.meta.url));

export function createEnvInjector(
  config: Partial<EnvInjectorChartInstanceConfig> & {
    values: EnvInjectorChartValues;
  },
): EnvInjectorChartInstanceConfig {
  return {
    name: defaultName,
    namespace: defaultName,
    path: joinPath(
      dirname(fromFileUrl(import.meta.url)),
      "../charts/env-injector",
    ),
    ...config,
  };
}
