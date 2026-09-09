import { useState } from 'react';

export default function LoginPage() {
  const [isLoading, setIsLoading] = useState(false);

  const handleLogin = async () => {
    setIsLoading(true);
    try {
      // TODO(PRODUCT_SPEC Section 5.1): implement sign in flow
      await new Promise(resolve => setTimeout(resolve, 1000));
    } catch (error) {
      console.error('Login failed:', error);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="max-w-md w-full space-y-8 p-8 bg-white rounded-lg shadow-md">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">DevFlow Login</h1>
          <p className="mt-2 text-sm text-gray-600">
            Trello for developers — one that understands your code
          </p>
        </div>

        <button
          onClick={handleLogin}
          disabled={isLoading}
          className="w-full flex justify-center py-2 px-4 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
        >
          {isLoading ? 'Signing in...' : 'Sign in with Google'}
        </button>

        <div className="text-center text-sm text-gray-500">
          <p>TODO(PRODUCT_SPEC Section 5.1): OAuth flow for accounts & workspaces</p>
        </div>
      </div>
    </div>
  );
}
