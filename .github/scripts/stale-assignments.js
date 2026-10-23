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

// Reminds contributors assigned to an issue without a linked pull request after 7 days, and unassigns them
// after 10, so the issue is free for someone else. A pull request linked to the issue stops the clock.
// Maintainers' own assignments are left alone. Run daily by .github/workflows/stale-assignments.yml.

const REMIND_AFTER_DAYS = 7;
const UNASSIGN_AFTER_DAYS = 10;
const REMINDER = '<!-- halyx-assign:reminder -->';
const DAY = 24 * 60 * 60 * 1000;

module.exports = async ({ github, context, core, now = new Date(), dryRun = false }) => {
  const { owner, repo } = context.repo;
  const issues = (await github.paginate(github.rest.issues.listForRepo, { owner, repo, state: 'open', assignee: '*' }))
    .filter((issue) => !issue.pull_request);

  for (const issue of issues) {
    const timeline = await github.paginate(github.rest.issues.listEventsForTimeline, {
      owner, repo, issue_number: issue.number,
    });
    for (const { login } of issue.assignees) {
      const { data: permission } = await github.rest.repos.getCollaboratorPermissionLevel({ owner, repo, username: login })
        .catch(() => ({ data: { permission: 'none' } }));
      if (['admin', 'maintain', 'write'].includes(permission.permission)) {
        continue;
      }
      const assigned = timeline.filter((event) => event.event === 'assigned' && event.assignee?.login === login).pop();
      if (!assigned) {
        continue;
      }
      const since = new Date(assigned.created_at);
      const linked = timeline.some((event) => new Date(event.created_at) >= since
        && (event.event === 'connected'
          || (event.event === 'cross-referenced' && event.source?.issue?.pull_request
            && event.source.issue.user?.login === login)));
      if (linked) {
        core.info(`#${issue.number}: ${login} has a linked pull request`);
        continue;
      }
      const days = (now - since) / DAY;
      const reminded = timeline.some((event) => event.event === 'commented' && new Date(event.created_at) >= since
        && String(event.body || '').includes(REMINDER));

      if (days >= UNASSIGN_AFTER_DAYS) {
        core.info(`#${issue.number}: unassigning ${login} after ${Math.floor(days)} days`);
        if (!dryRun) {
          await github.rest.issues.removeAssignees({ owner, repo, issue_number: issue.number, assignees: [login] });
          await github.rest.issues.createComment({
            owner, repo, issue_number: issue.number,
            body: `@${login}, I've unassigned this because there's no linked pull request after ${UNASSIGN_AFTER_DAYS} `
              + `days, so someone else can pick it up. Thanks for your interest; you're welcome to ask for it again, `
              + `or for another issue, when you have time.\n\nThis issue is open for contributors again: comment `
              + `\`/assign\` to take it.`,
          });
        }
      } else if (days >= REMIND_AFTER_DAYS && !reminded) {
        core.info(`#${issue.number}: reminding ${login} after ${Math.floor(days)} days`);
        if (!dryRun) {
          await github.rest.issues.createComment({
            owner, repo, issue_number: issue.number,
            body: `${REMINDER}\n@${login}, how is this going? Please open a draft pull request linked to this issue `
              + `(with "Fixes #${issue.number}" in its description), even if it's not finished. Without one, the `
              + `issue is unassigned in ${UNASSIGN_AFTER_DAYS - REMIND_AFTER_DAYS} days so someone else can pick it up.`,
          });
        }
      } else {
        core.info(`#${issue.number}: ${login} assigned ${Math.floor(days)} days ago`);
      }
    }
  }
};
