{{- define "corvid.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "corvid.labels" -}}
app.kubernetes.io/name: corvid
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "corvid.selector" -}}
app.kubernetes.io/name: corvid
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "corvid.secret" -}}
{{- .Values.secrets.existingSecret | default (printf "%s-secrets" (include "corvid.fullname" .)) -}}
{{- end -}}

{{- define "corvid.image" -}}
{{- $repo := required "images.corvid.repository is required: push the image built from deploy/Containerfile to a registry the cluster can pull from" .Values.images.corvid.repository -}}
{{- printf "%s:%s" $repo .Values.images.corvid.tag -}}
{{- end -}}

{{- define "corvid.webPort" -}}
{{- $port := .Values.config.server.web_port | default 4443 | int -}}
{{- if has $port (list 25 80 465 587 993 1411 5335 8080 8081 8443 11332 11333) -}}
{{- fail (printf "config.server.web_port %d is taken inside the pod; choose another" $port) -}}
{{- end -}}
{{- $port -}}
{{- end -}}

{{- define "corvid.hostname" -}}
{{- required "config.server.hostname is required" .Values.config.server.hostname -}}
{{- end -}}
