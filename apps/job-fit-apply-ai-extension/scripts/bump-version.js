#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

// Parse command line arguments for bump type
const args = process.argv.slice(2);
let bumpType = 'patch'; // default

if (args.includes('--major')) {
  bumpType = 'major';
} else if (args.includes('--minor')) {
  bumpType = 'minor';
} else if (args.includes('--patch')) {
  bumpType = 'patch';
}

// Read manifest.json
const manifestPath = path.join(__dirname, '..', 'manifest.json');
const manifestContent = fs.readFileSync(manifestPath, 'utf8');
const manifest = JSON.parse(manifestContent);

// Read package.json
const packagePath = path.join(__dirname, '..', 'package.json');
const packageContent = fs.readFileSync(packagePath, 'utf8');
const packageJson = JSON.parse(packageContent);

// Get current version from manifest (source of truth)
const currentVersion = manifest.version;

// Parse semver
const [major, minor, patch] = currentVersion.split('.').map(Number);

// Increment version based on bump type
let newMajor = major;
let newMinor = minor;
let newPatch = patch;

switch (bumpType) {
  case 'major':
    newMajor = major + 1;
    newMinor = 0;
    newPatch = 0;
    break;
  case 'minor':
    newMinor = minor + 1;
    newPatch = 0;
    break;
  case 'patch':
  default:
    newPatch = patch + 1;
    break;
}

const newVersion = `${newMajor}.${newMinor}.${newPatch}`;

// Update manifest.json
manifest.version = newVersion;
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');

// Update package.json
packageJson.version = newVersion;
fs.writeFileSync(packagePath, JSON.stringify(packageJson, null, 2) + '\n');

// Output new version
console.log(`Version bumped: ${currentVersion} -> ${newVersion} (${bumpType})`);

// Export new version for use by hook
process.stdout.write(newVersion);
