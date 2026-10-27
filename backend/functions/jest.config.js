// firebase-admin 14 trae dependencias que Jest 29 no carga tal cual: jose 6 es solo ESM y gaxios y teeny-request hacen
// import() dinamico de node-fetch 3 (tambien solo ESM). ts-jest las pasa a CommonJS (el import() queda en require) y
// node-fetch se sustituye por la 2 (CommonJS, misma API y con cuerpos en stream, que el fetch de Node no acepta para las
// subidas de Storage). Solo afecta a los tests: en Cloud Functions, Node 22 carga las originales sin ayuda.
const esm = ["jose", "gaxios", "teeny-request"].join("|");

module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  transform: { "^.+\\.[tj]s$": ["ts-jest", { tsconfig: "<rootDir>/tsconfig.jest.json" }] },
  transformIgnorePatterns: [`node_modules/(?!(.*/node_modules/)?(${esm})/)`],
  moduleNameMapper: { "^node-fetch$": "node-fetch-cjs" },
};
