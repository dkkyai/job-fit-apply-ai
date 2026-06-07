# job-backlog

![CI](https://github.com/<owner>/<repo>/actions/workflows/ci.yml/badge.svg)

A job tracking dashboard built with Vite, React, and Supabase.

## Features

- Track job applications with company, role title, location, and tech stack
- Visual fit score for job matches
- Status management (backlog, applied, interview, offer, rejected)
- Real-time data synchronization with Supabase

## Prerequisites

- Node.js 18+ or Bun 1.0+
- Git
- A Supabase account (optional - defaults to demo project)

## Local Development Setup

### 1. Clone the repository

```bash
git clone <repository-url>
cd job-fit-apply-ai-backlog
```

### 2. Install dependencies

Using npm:
```bash
npm install
```

Or using Bun:
```bash
bun install
```

### 3. Configure environment variables

Copy the example environment file:
```bash
cp .env.example .env
```

Edit `.env` and fill in your Supabase credentials (optional - the demo project is pre-configured):
- `VITE_SUPABASE_URL`: Your Supabase project URL
- `VITE_SUPABASE_ANON_KEY`: Your Supabase anon key

Get these from your [Supabase project settings](https://supabase.com/dashboard).

### 4. Start the development server

```bash
npm run dev
# or
bun run dev
```

The application will be available at `http://localhost:8080`.

## Testing

### Running Tests

This project uses Vitest for unit testing and Playwright for end-to-end testing.

| Command | Description |
|---------|-------------|
| `npm run test:unit` | Run unit tests with coverage |
| `npm run test:e2e` | Run end-to-end tests with Playwright |
| `npm test` | Run all tests (unit + e2e) |

### Coverage Reports

Unit test coverage reports are generated in the `coverage/` directory after running `npm run test:unit` or `npm test`. Open `coverage/index.html` in your browser to view the interactive coverage report.

### Playwright Setup

Before running E2E tests for the first time, install Playwright browsers:

```bash
npx playwright install
```

To install browsers with system dependencies (required for some Linux environments):

```bash
npx playwright install --with-deps
```

## CI/CD Pipeline

### GitHub Actions Workflow

The project uses GitHub Actions for continuous integration and deployment. The workflow is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

#### Workflow Jobs

1. **lint-and-unit**: Runs ESLint and unit tests on Node.js 18.x and 20.x
2. **build**: Builds the project after lint and unit tests pass
3. **e2e**: Runs Playwright end-to-end tests against the built application
4. **deploy**: Deploys to Vercel when changes are merged to `main` branch

#### Workflow Triggers

- Runs on every push to any branch
- Runs on pull requests targeting `main` branch

### Setting Up GitHub Secrets

To enable full CI/CD functionality with Supabase integration, configure the following secrets in your GitHub repository settings:

| Secret | Description |
|--------|-------------|
| `VITE_SUPABASE_URL` | Your Supabase project URL |
| `VITE_SUPABASE_ANON_KEY` | Your Supabase anon key |
| `VERCEL_TOKEN` | Vercel API token for deployment |
| `VERCEL_ORG_ID` | Vercel organization ID |
| `VERCEL_PROJECT_ID` | Vercel project ID |

#### Getting Supabase Secrets

1. Go to your [Supabase dashboard](https://supabase.com/dashboard)
2. Select your project
3. Navigate to **Project Settings** → **API**
4. Copy the following values:
   - **Project URL** → `VITE_SUPABASE_URL`
   - **anon/public key** → `VITE_SUPABASE_ANON_KEY`

#### Setting Secrets via GitHub CLI (Recommended)

If you have [GitHub CLI](https://cli.github.com/) installed:

```bash
# Make sure you're authenticated
gh auth login

# Set the secrets (run in your project directory)
gh secret set VITE_SUPABASE_URL --body 'your-supabase-url'
gh secret set VITE_SUPABASE_ANON_KEY --body 'your-supabase-anon-key'
```

For convenience, use the provided setup script:

```bash
./scripts/setup-github-secrets.sh
```

#### Setting Secrets via GitHub UI

1. Go to your repository on GitHub
2. Click on **Settings** tab
3. In the left sidebar, click on **Secrets and variables** → **Actions**
4. Click **New repository secret**
5. Add the following secrets:

| Name | Value |
|-----|-----|
| `VITE_SUPABASE_URL` | Your Supabase project URL (e.g., `https://xxx.supabase.co`) |
| `VITE_SUPABASE_ANON_KEY` | Your Supabase anon key from Supabase dashboard |

6. Click **Add secret** for each

#### Verification

To verify your secrets are set correctly:

```bash
gh secret list
```

> **Note**: These secrets are required for E2E tests to run against a real Supabase instance during CI/CD. Without them, E2E tests will skip or fail.

## Database Schema

If using your own Supabase project, run the following SQL to create the `tracks` table:

```sql
CREATE TABLE tracks (
  id bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
  company text NOT NULL,
  role_title text NOT NULL,
  location text,
  remote_policy text,
  fit_score integer,
  job_url text,
  artifact_url text,
  tech_stack text[],
  status text NOT NULL DEFAULT 'backlog',
  created_at timestamptz DEFAULT now(),
  duplicate boolean DEFAULT false
);
```

## Cloud Deployment

### Deploy to Vercel (Recommended)

1. Push your code to a Git repository (GitHub, GitLab, or Bitbucket)
2. Sign up at [vercel.com](https://vercel.com) and import your repository
3. Add environment variables in Vercel dashboard:
   - `VITE_SUPABASE_URL`
   - `VITE_SUPABASE_ANON_KEY`
4. Deploy!

### Deploy to Netlify

1. Push your code to a Git repository
2. Sign up at [netlify.com](https://netlify.com) and import your repository
3. Add environment variables in Netlify dashboard
4. Set build settings:
   - Build Command: `npm run build`
   - Publish Directory: `dist`

## Available Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start development server |
| `npm run build` | Build for production |
| `npm run build:dev` | Build for development mode |
| `npm run preview` | Preview production build locally |
| `npm run lint` | Run ESLint |
| `npm run test` | Run all tests (unit + e2e) |
| `npm run test:unit` | Run unit tests with coverage |
| `npm run test:watch` | Run unit tests in watch mode |
| `npm run test:e2e` | Run Playwright E2E tests |
| `npm run test:ci` | Run full CI pipeline locally |

## Contribution Guidelines

We welcome contributions! Here's how to get started:

### Getting Started

1. **Fork the repository**
   ```bash
   # Click the "Fork" button on GitHub, then:
    git clone https://github.com/<your-username>/job-fit-apply-ai-backlog.git
   cd job-fit-apply-ai-backlog
   ```

2. **Create a branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Install dependencies**
   ```bash
   npm install
   ```

4. **Make your changes**

5. **Run tests**
   ```bash
   npm test
   ```
   Ensure all tests pass before committing.

6. **Format and lint**
   ```bash
   npm run lint
   ```

7. **Commit your changes**
   ```bash
   git commit -m "feat: add some feature"
   ```
   Follow the [Conventional Commits](https://www.conventionalcommits.org/) format.

8. **Push to your fork**
   ```bash
   git push origin feature/your-feature-name
   ```

9. **Open a Pull Request**

### Code Style

This project enforces code quality using:

- **ESLint**: Lints TypeScript and React code. Configuration in [`eslint.config.js`](eslint.config.js)
- **Prettier**: Code formatting. Run `npm run lint` to apply formatting

### Requirements for Pull Requests

- All tests must pass (unit and E2E)
- Code must be linted and formatted
- New features should include tests
- Update documentation if applicable

## Troubleshooting

### CORS Errors

Verify that your Supabase project has the correct site URL allowed in the Supabase dashboard (Settings → API).

### Empty Data

If the `tracks` table is empty, you may need to import sample data or create the schema (see Database Schema section).

### Environment Variables Not Loading

Ensure your `.env` file is in the project root and Vite prefixes environment variables with `VITE_`.

### Playwright Browser Errors

If you encounter Playwright browser errors, ensure browsers are installed:
```bash
npx playwright install
```

## License

MIT
