/**
 * tests/extractors/jd_extractor.test.js
 *
 * Unit tests for extractors/jd_extractor.js
 */

const { JSDOM } = require('jsdom');

// Patch Element.prototype.innerText for JSDOM (it doesn't implement innerText properly)
// This ensures inner() helper tests work correctly
const originalGetter = Object.getOwnPropertyDescriptor(Element.prototype, 'innerText');
Object.defineProperty(Element.prototype, 'innerText', {
  get() {
    return this.textContent;
  },
  configurable: true,
});

afterAll(() => {
  // Restore original innerText if it existed
  if (originalGetter) {
    Object.defineProperty(Element.prototype, 'innerText', originalGetter);
  }
});

// Helper to create a minimal DOM from HTML string
function createDoc(html) {
  const dom = new JSDOM(html);
  const { window } = dom;
  
  // Patch innerText on JSDOM's Element prototype (JSDOM doesn't implement innerText)
  // This must be done on the JSDOM window's prototype chain, not the global one
  const originalDescriptor = Object.getOwnPropertyDescriptor(window.Element.prototype, 'innerText');
  Object.defineProperty(window.Element.prototype, 'innerText', {
    get() {
      return this.textContent;
    },
    configurable: true,
  });
  
  return window.document;
}

// Load the extractor module
const extractorPath = require.resolve('../../extractors/jd_extractor.js');
const { EXTRACTORS, pickExtractor, t, inner } = require(extractorPath);

describe('JD Extractor', () => {
  describe('t() helper', () => {
    it('returns trimmed text content of first matching selector', () => {
      const doc = createDoc('<div><span id="title">  Senior SDET  </span></div>');
      expect(t(doc, '#title')).toBe('Senior SDET');
    });

    it('returns null when no selector matches', () => {
      const doc = createDoc('<div>Hello</div>');
      expect(t(doc, '#nonexistent')).toBeNull();
    });

    it('returns null for empty elements', () => {
      const doc = createDoc('<div id="empty"></div>');
      expect(t(doc, '#empty')).toBeNull();
    });
  });

  describe('inner() helper', () => {
    it('returns innerText of first selector with substantial content', () => {
      const doc = createDoc(`
        <article>
          <p class="desc">This is a job description with lots of content about the role.</p>
        </article>
      `);
      expect(inner(doc, '.desc')).toBe('This is a job description with lots of content about the role.');
    });

    it('returns null when no selector has > 50 chars', () => {
      const doc = createDoc('<p class="short">Short</p>');
      expect(inner(doc, '.short')).toBeNull();
    });

    it('tries multiple selectors and returns first hit', () => {
      const doc = createDoc('<div class="second">Good content here that is more than fifty characters for the test</div>');
      expect(inner(doc, '.first, .second')).toBeTruthy();
    });
  });

  describe('pickExtractor()', () => {
    it('returns greenhouse for greenhouse.io', () => {
      expect(pickExtractor('jobs.greenhouse.io')).toBe('greenhouse');
      expect(pickExtractor('apply.greenhouse.io')).toBe('greenhouse');
    });

    it('returns lever for lever.co', () => {
      expect(pickExtractor('jobs.lever.co')).toBe('lever');
      expect(pickExtractor('apply.lever.co')).toBe('lever');
    });

    it('returns workday for workday.com', () => {
      expect(pickExtractor('wd3.myworkday.com')).toBe('workday');
      expect(pickExtractor('workday.com')).toBe('workday');
    });

    it('returns linkedin for linkedin.com', () => {
      expect(pickExtractor('www.linkedin.com')).toBe('linkedin');
      expect(pickExtractor('linkedin.com/jobs')).toBe('linkedin');
    });

    it('returns indeed for indeed.com', () => {
      expect(pickExtractor('www.indeed.com')).toBe('indeed');
    });

    it('returns glassdoor for glassdoor.com', () => {
      expect(pickExtractor('www.glassdoor.com')).toBe('glassdoor');
    });

    it('returns ashby for ashbyhq.com', () => {
      expect(pickExtractor('jobs.ashbyhq.com')).toBe('ashby');
    });

    it('returns workable for workable.com', () => {
      expect(pickExtractor('apply.workable.com')).toBe('workable');
    });

    it('returns smartrecruiters for smartrecruiters.com', () => {
      expect(pickExtractor('jobs.smartrecruiters.com')).toBe('smartrecruiters');
    });

    it('returns icims for icims.com', () => {
      expect(pickExtractor('jobs.icims.com')).toBe('icims');
    });

    it('returns bamboohr for bamboohr.com', () => {
      expect(pickExtractor('*.bamboohr.com')).toBe('bamboohr');
    });

    it('returns jazzhr for jazz.co', () => {
      expect(pickExtractor('apply.jazz.co')).toBe('jazzhr');
    });

    it('returns generic for unknown sites', () => {
      expect(pickExtractor('example.com')).toBe('generic');
      expect(pickExtractor('randomjobsite.com')).toBe('generic');
    });
  });

  describe('Greenhouse extractor', () => {
    const extractor = EXTRACTORS.greenhouse;

    it('extracts title, company, location, and body', () => {
      const doc = createDoc(`
        <div id="app_body">
          <h1 class="app-title h1">Senior SDET Engineer</h1>
          <span class="company-name">Acme Corp</span>
          <span class="location">Seattle, WA</span>
          <div id="content">
            We are looking for a talented Senior SDET Engineer to join our team.
            You will be responsible for building and maintaining test automation frameworks.
            The ideal candidate has 5+ years of experience in test automation.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Senior SDET Engineer');
      expect(result.company).toBe('Acme Corp');
      expect(result.location).toBe('Seattle, WA');
      expect(result.body).toContain('Senior SDET Engineer');
    });
  });

  describe('Lever extractor', () => {
    const extractor = EXTRACTORS.lever;

    it('extracts fields from Lever job pages', () => {
      const doc = createDoc(`
        <div class="posting-headline">
          <h2>Staff QA Engineer</h2>
        </div>
        <div class="main-header-text">
          <span class="posting-category">Acme Labs</span>
        </div>
        <div class="posting-categories">
          <span class="location">Remote</span>
        </div>
        <div class="posting-page">
          <div class="section-wrapper">
            <div class="posting-description">
              We need a Staff QA Engineer to lead our quality initiatives.
            </div>
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Staff QA Engineer');
      expect(result.body).toContain('Staff QA Engineer');
    });
  });

  describe('LinkedIn extractor', () => {
    const extractor = EXTRACTORS.linkedin;

    it('extracts fields from LinkedIn job pages', () => {
      const doc = createDoc(`
        <div class="jobs-unified-top-card">
          <h1 class="jobs-unified-top-card__job-title">Principal SDET</h1>
          <span class="jobs-unified-top-card__company-name">
            <a href="/company/acme">Acme Inc</a>
          </span>
          <span class="jobs-unified-top-card__bullet">San Francisco, CA</span>
        </div>
        <div class="jobs-description-content__text">
          <p>Job description content here with lots of details about the role...</p>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Principal SDET');
      expect(result.company).toContain('Acme Inc');
      expect(result.body).toContain('Job description');
    });
  });

  describe('Indeed extractor', () => {
    const extractor = EXTRACTORS.indeed;

    it('extracts fields from Indeed job pages', () => {
      const doc = createDoc(`
        <div>
          <h1 id="jobsearch-JobInfoHeader-title" data-testid="jobsearch-JobInfoHeader-title">
            Test Automation Engineer
          </h1>
          <div data-testid="inlineHeader-companyName">
            <a href="/company/indeed">Indeed</a>
          </div>
          <div data-testid="job-location">Austin, TX</div>
          <div id="jobDescriptionText">
            This is a great opportunity for a test automation engineer.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Test Automation Engineer');
      expect(result.body).toContain('test automation engineer');
    });
  });

  describe('Generic extractor', () => {
    const extractor = EXTRACTORS.generic;

    it('falls back to generic extraction with heuristics', () => {
      const doc = createDoc(`
        <main>
          <h1>QA Lead Position</h1>
          <div id="company-name">TechCorp</div>
          <div id="job-description">
            <p>We are hiring a QA Lead to manage our test team.</p>
            <p>The ideal candidate has experience with Selenium, Cypress, and test strategy.</p>
          </div>
        </main>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('QA Lead Position');
      expect(result.body.length).toBeGreaterThan(100);
    });

    it('filters out elements with less than 300 characters', () => {
      const doc = createDoc(`
        <div>
          <p class="sidebar">Short text</p>
          <article id="job-description">
            This is a much longer job description that contains detailed information about the role,
            responsibilities, requirements, and qualifications for the position. It should be
            more than three hundred characters long to pass the filter test and ensure the content
            extraction works properly with sufficient text content in the job description section.
          </article>
        </div>
      `);
      const result = extractor(doc);
      expect(result.body.length).toBeGreaterThanOrEqual(300);
    });
  });

  describe('__ocExtractJD integration', () => {
    beforeEach(() => {
      // Set up window environment for extractor
      global.window = {
        location: { href: 'https://www.linkedin.com/jobs/view/123', hostname: 'www.linkedin.com' },
        document: createDoc('<html><body></body></html>'),
      };
    });

    it('exposes __ocExtractJD as a global function', () => {
      // Load the extractor which should define window.__ocExtractJD
      require(extractorPath);
      expect(typeof window.__ocExtractJD).toBe('function');
    });
  });
});
