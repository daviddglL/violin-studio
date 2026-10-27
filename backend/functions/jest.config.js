// jose 6 (de firebase-admin 14, vía jwks-rsa) solo se publica como ESM; Jest 29 no carga ESM con require, así que se
// transforma a CommonJS con ts-jest como el resto del código.
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  transform: { "^.+\\.[tj]s$": ["ts-jest", { tsconfig: "<rootDir>/tsconfig.jest.json" }] },
  transformIgnorePatterns: ["/node_modules/(?!jose/)"],
};
