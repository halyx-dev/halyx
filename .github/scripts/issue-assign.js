/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Assigns an issue to a contributor who asks for it, following CONTRIBUTING.md: only issues labelled
// "good first issue" or "help wanted", not while someone else's pull request for it is open, one open issue per
// contributor at a time, and a plan first for larger "help wanted" issues. The author of an open pull request for
// the issue can always claim it. Run by .github/workflows/issue-assign.yml on every new issue comment.

const GOOD_FIRST = 'good first issue';
const HELP_WANTED = 'help wanted';
const NEEDS_PLAN = 'needs-plan';
const CONTRIBUTING = 'https://github.com/halyx-dev/halyx/blob/main/CONTRIBUTING.md';

// "/assign" on a line of its own, or a plain request such as "could you assign this to me?".
const CLAIMS = [
  /^\s*\/assign\s*$/m,
  /\bassign (?:this|it|the issue)? ?to me\b/,
  /\bassign me\b/,
  /\bi(?:'d| would) (?:like|love|be happy) to (?:work on|take|pick up|contribute to|handle)\b/,
  /\b(?:can|could|may) i (?:work on|take|pick up|handle|have)\b/,
  /\bi(?:'m| am) interested in (?:working on|contributing|taking)\b/,
  /\bis (?:this|it) (?:still )?(?:available|open|free|up for grabs)\b/,
];

function isClaim(body) {
  const text = String(body || '').toLowerCase().replace(/[‘’]/g, "'");
  return CLAIMS.some((pattern) => pattern.test(text));
}

// Open pull requests that close the issue when merged: "Closes #N" in their description, or linked by hand.
async function openClosingPulls(github, owner, repo, number) {
  const result = await github.graphql(`query($owner: String!, $repo: String!, $number: Int!) {
    repository(owner: $owner, name: $repo) {
      issue(number: $number) {
        closedByPullRequestsReferences(first: 10, includeClosedPrs: false) { nodes { number author { login } } }
      }
    }
  }`, { owner, repo, number });
  return result.repository.issue.closedByPullRequestsReferences.nodes;
}

module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const issue = context.payload.issue;
  const comment = context.payload.comment;
  const login = comment.user.login;

  if (!isClaim(comment.body)) {
    core.info(`Comment by ${login} is not a request to be assigned`);
    return;
  }
  const labels = issue.labels.map((label) => (typeof label === 'string' ? label : label.name));
  if (issue.state !== 'open' || !(labels.includes(GOOD_FIRST) || labels.includes(HELP_WANTED))) {
    core.info(`#${issue.number} isn't open for contributors; leaving it to the maintainer`);
    return;
  }

  const reply = (body) => github.rest.issues.createComment({ owner, repo, issue_number: issue.number, body });
  const assignees = issue.assignees.map((user) => user.login);

  if (assignees.includes(login)) {
    core.info(`#${issue.number} is already assigned to ${login}`);
    return;
  }
  if (assignees.length > 0) {
    await reply(`Thanks @${login}! This one is already assigned to @${assignees[0]}. If it frees up, it's open again `
      + `and you're welcome to ask then. Other [good first issues](https://github.com/${owner}/${repo}/labels/`
      + `good%20first%20issue) may be free.`);
    return;
  }

  // Someone else's open pull request already does this issue; the author of one may still claim it.
  const pulls = await openClosingPulls(github, owner, repo, issue.number);
  const ownPull = pulls.some((pull) => pull.author?.login === login);
  if (pulls.length > 0 && !ownPull) {
    await reply(`Thanks @${login}! There's already an open pull request for this, #${pulls[0].number} by `
      + `@${pulls[0].author?.login}. If it's closed without being merged, this issue is open again. Other `
      + `[good first issues](https://github.com/${owner}/${repo}/labels/good%20first%20issue) may be free.`);
    return;
  }

  const theirs = (await github.paginate(github.rest.issues.listForRepo, { owner, repo, state: 'open', assignee: login }))
    .filter((other) => other.number !== issue.number && !other.pull_request);
  if (theirs.length > 0 && !ownPull) {
    await reply(`Thanks @${login}! To give more people a chance, each contributor has one issue at a time, and you `
      + `have #${theirs[0].number}. Once that's merged, you're welcome to take this one if it's still free.`);
    return;
  }

  if (!labels.includes(GOOD_FIRST) && !ownPull) {
    await github.rest.issues.addLabels({ owner, repo, issue_number: issue.number, labels: [NEEDS_PLAN] });
    await reply(`Thanks @${login}! This is a larger change, so before it's assigned, please comment here with a short `
      + `plan: an API sketch and how you'd test it on each provider. The maintainer will assign it once we've agreed `
      + `on the approach.`);
    return;
  }

  const { data: updated } = await github.rest.issues.addAssignees({
    owner, repo, issue_number: issue.number, assignees: [login],
  });
  if (!updated.assignees.some((user) => user.login === login)) {
    // GitHub drops assignees it doesn't allow without an error.
    core.warning(`GitHub didn't assign #${issue.number} to ${login}`);
    await reply(`Thanks @${login}! I couldn't assign this automatically; the maintainer will do it shortly.`);
    return;
  }
  await reply(`Thanks @${login}, it's yours. Please open a draft pull request within a week, even if it's not `
    + `finished, so the direction can be checked early; issues without a linked pull request after 10 days are `
    + `unassigned so someone else can pick them up. [CONTRIBUTING.md](${CONTRIBUTING}) has the build steps.`);
};

module.exports.isClaim = isClaim;
