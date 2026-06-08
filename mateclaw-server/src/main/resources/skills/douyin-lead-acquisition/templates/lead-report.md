# Douyin Lead Report

## Campaign

- Query: {{query}}
- Sort: {{sort}}
- Videos processed: {{summary.videosProcessed}}
- Comments collected: {{summary.commentsCollected}}
- Declared comments: {{summary.declaredCommentCount}}
- Remaining declared comments: {{summary.remainingDeclaredComments}}
- Collection complete: {{summary.collectionComplete}}
- Collection coverage: {{summary.collectionCoverage}}
- Collection stop reason: {{summary.collectionStopReason}}
- Comments matched: {{summary.commentsMatched}}
- DM drafts typed: {{summary.dmDraftsTyped}}

## Matched Comments

{{#matchedComments}}
### {{comment.authorName}}

- Video: {{video.url}}
- Comment: {{comment.text}}
- Match score: {{match.score}}
- Reason: {{match.reason}}
{{/matchedComments}}

## Engagements

{{#engagements}}
- {{actionType}} / {{status}} / {{commentKey}} / {{failureCode}}
{{/engagements}}
