// Production real-mode build boundary. No fixtures, schemas or mock state ship.
const unavailable = (): never => {
  throw new Error("Mock transport is disabled in real API mode");
};
export const mockFetch: typeof fetch = async () => unavailable();
export const mockStoragePut = async (..._args: unknown[]): Promise<void> =>
  unavailable();
export const setMockScenario = (_scenario: string) => unavailable();
export const resetMock = () => unavailable();
export const mockResetLink = (): null => null;
