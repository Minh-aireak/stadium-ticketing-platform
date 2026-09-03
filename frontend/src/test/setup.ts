import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// @testing-library/react only registers its own afterEach cleanup when vitest is running with
// `globals: true`. This project imports describe/it/expect explicitly instead, so without this
// file every rendered tree stays in the document and the next test's getByRole finds two.
afterEach(cleanup)
