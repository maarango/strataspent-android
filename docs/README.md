# GitHub Pages site for StrataSpent

This folder is served by GitHub Pages as the public website for the
project. Its only required purpose is to host the **privacy policy URL**
that Play Console will want when submitting the app for review.

## Files

| File | Purpose |
|---|---|
| `index.html` | Landing page (visited at `https://USER.github.io/REPO/`) |
| `privacy-policy.html` | Privacy policy at `https://USER.github.io/REPO/privacy-policy.html` |
| `.nojekyll` | Tells GitHub Pages to skip Jekyll and serve files as-is |

## Enable Pages (one-time setup)

After pushing the repo to GitHub:

1. Open the repo on github.com
2. **Settings** → **Pages** (left sidebar)
3. Under **Build and deployment**:
   - Source: **Deploy from a branch**
   - Branch: **main** (or whichever default), folder: **/docs**
4. **Save**
5. Wait ~60 seconds. GitHub will surface the public URL at the top of the
   page once the first build finishes.

The Play Console privacy-policy URL to paste is then:

```
https://USER.github.io/REPO/privacy-policy.html
```

(Replace `USER` and `REPO` with your GitHub username and repository name.)

## Updating

Edits to `privacy-policy.html` go live on the next push to `main` —
typically within 1–2 minutes. No manual deploy step.
