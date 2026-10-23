export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
  turnstileToken?: string | null;
}

export interface RegisterResponse {
  email: string;
  status: string;
  message: string;
}

export interface MessageResponse {
  message: string;
}
