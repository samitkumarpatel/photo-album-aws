/*
 * Shared fetch helpers.
 *
 * VITE_API_BASE_URL is set by the frontend deployment workflow. Leaving it empty keeps local development on the
 * Vite `/api` proxy.
 */

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

export async function apiFetch(url, options = {}) {
  const apiUrl = API_BASE_URL && url.startsWith('/api/') ? `${API_BASE_URL}${url}` : url
  return fetch(apiUrl, options)
}

export function errorMessage(status, body = {}, fallback = 'Something went wrong. Please try again.') {
  return body.detail || body.message || (status === 410 ? 'This share link has expired.' : status === 413 ? 'The file is larger than 100 MB.' : status === 415 ? 'This file type isn’t supported.' : fallback)
}

export async function api(path, options = {}) {
  const response = await apiFetch(path, options)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const error = new Error(errorMessage(response.status, body))
    error.status = response.status
    throw error
  }
  return response.status === 204 ? null : response.json()
}
