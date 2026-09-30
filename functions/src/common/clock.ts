/** Reloj inyectable para poder probar caducidades y edades con tiempo fijo. */
export type Clock = () => Date;

export const systemClock: Clock = () => new Date();
