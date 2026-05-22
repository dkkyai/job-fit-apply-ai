/**
 * tests/setup.js
 *
 * Jest setup file for jsdom environment.
 * Mocks the Chrome extension API for testing.
 */

// Polyfill for TextEncoder (required by jsdom in Node.js 18+)
const { TextEncoder, TextDecoder } = require('util');
global.TextEncoder = TextEncoder;
global.TextDecoder = TextDecoder;

// Mock chrome.runtime
global.chrome = {
  runtime: {
    onMessage: {
      addListener: jest.fn(),
    },
    sendMessage: jest.fn(),
    lastError: null,
  },
};

// Mock window.location
delete global.location;
global.location = new URL('https://www.linkedin.com/jobs/view/123');

// Mock window.document
const { JSDOM } = require('jsdom');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>');
global.document = dom.window.document;
global.window = dom.window;
global.Node = dom.window.Node;
