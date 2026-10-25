/** Nombre del bucket por defecto: el configurado, o `<projectId>.appspot.com` como respaldo explícito. */
export function resolveBucketName(
  options: { storageBucket?: string; projectId?: string },
  env: NodeJS.ProcessEnv = process.env,
): string {
  if (options.storageBucket) return options.storageBucket;
  const projectId = options.projectId ?? env.GCLOUD_PROJECT;
  if (!projectId) throw new Error("No se puede resolver el bucket de Storage");
  return `${projectId}.appspot.com`;
}
