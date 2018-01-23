# Get You A License

A little web app that makes it easy to add licenses to projects on GitHub. Check it out:

![Get You a License](docs/get-you-a-license.gif)

Run on Heroku:
- Create a GitHub OAuth App, setting the callback URL to something temporary
- Deploy: [![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy)
- Go back to the GitHub OAuth App settings and update the callback url to `https://YOUR_HEROKU_APP.herokuapp.com/_github_oauth_callback`

Run Locally:

- Create a GitHub OAuth App, setting the callback URL to `http://localhost:9000/_github_oauth_callback`
- Create a repo for testing
- Set env vars:

        export GITHUB_OAUTH_CLIENT_ID=BLAH
        export GITHUB_OAUTH_CLIENT_SECRET=BLAH
        export GITHUB_TEST_TOKEN=BLAH
        export GITHUB_TEST_ORG=BLAH
        export GITHUB_TEST_REPO=BLAH
        export GITHUB_TEST_USER=BLAH

- Run tests:

    ./sbt test

- Run Web App:

    ./sbt ~run

- Try it out: [http://localhost:9000](http://localhost:9000)
