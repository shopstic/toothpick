{{- define "env-injector.mutatingWebhookConfiguration" }}
apiVersion: admissionregistration.k8s.io/v1
kind: MutatingWebhookConfiguration
metadata:
  name: {{ include "env-injector.fullname" . }}
  ownerReferences:
  - apiVersion: v1
    blockOwnerDeletion: true
    controller: true
    kind: Namespace
    name: {{ .Release.Namespace }}
    uid: %%OWNER_NAMESPACE_UID%%
webhooks:
  - name: {{ include "env-injector.fullname" . }}.{{ .Release.Namespace }}.svc.cluster.local
    clientConfig:
      caBundle: %%CA_BUNDLE%%
      service:
        namespace: {{ .Release.Namespace }}
        name: {{ include "env-injector.fullname" . }}
        path: "/mutate"
        port: {{ .Values.service.port }}
    timeoutSeconds: 5
    sideEffects: None
    {{- with .Values.mutationWebhook.namespaceSelector }}
    namespaceSelector:
      {{- toYaml . | nindent 6 }}
    {{- end }}
    objectSelector:
      matchExpressions:
        - key: "shopstic.com/env-injector-controller"
          operator: NotIn
          values: ["true"]
        {{- with .Values.mutationWebhook.objectSelector.matchExpressions }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- with .Values.mutationWebhook.objectSelector.matchLabels }}
      matchLabels:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    rules:
      - operations: ["CREATE"]
        apiGroups: ["*"]
        apiVersions: ["*"]
        resources: ["pods"]
        scope: "Namespaced"
    admissionReviewVersions: ["v1"]
{{- end }}