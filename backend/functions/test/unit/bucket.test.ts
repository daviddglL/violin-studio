import { resolveBucketName } from "../../src/erasure/bucket";

test("usa el bucket configurado si existe", () => {
  expect(resolveBucketName({ storageBucket: "b.appspot.com", projectId: "p" }, {})).toBe("b.appspot.com");
});
test("sin bucket: projectId de las opciones", () => {
  expect(resolveBucketName({ projectId: "p" }, { GCLOUD_PROJECT: "q" })).toBe("p.appspot.com");
});
test("sin bucket ni projectId en opciones: GCLOUD_PROJECT", () => {
  expect(resolveBucketName({}, { GCLOUD_PROJECT: "q" })).toBe("q.appspot.com");
});
test("sin nada resoluble lanza", () => {
  expect(() => resolveBucketName({}, {})).toThrow();
});
