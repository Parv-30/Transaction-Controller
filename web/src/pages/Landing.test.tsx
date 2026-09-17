import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Landing } from './Landing';

const mockUseAuth = vi.fn();
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => mockUseAuth(),
}));

describe('Landing', () => {
  it('shows the hero and login call-to-action when not authenticated', () => {
    mockUseAuth.mockReturnValue({ user: null, isLoading: false, roles: [], login: vi.fn() });

    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: /log in/i })).toBeInTheDocument();
  });

  it('does not show the login button once authenticated (redirect takes over)', () => {
    mockUseAuth.mockReturnValue({
      user: { profile: { realm_access: { roles: ['user'] } } },
      isLoading: false,
      roles: ['user'],
      login: vi.fn(),
    });

    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('button', { name: /log in/i })).not.toBeInTheDocument();
  });
});
