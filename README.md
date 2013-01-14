# Syme

Instant collaboration on GitHub projects.

## Usage

1. Enter the name of a GitHub repo.
   (Authorize Syme via GitHub if you haven't already.)
2. Enter your AWS credentials and names of GitHub users to invite.
3. Wait for your instance to boot.
4. SSH into the instance using the command shown and launch `tmux`.
5. Send the login info to the users you have invited.

Syme handles launching the instance, setting up public keys, and
cloning the repository in question.

Your AWS credentials are kept in an encrypted cookie in your browser
and aren't stored server-side beyond the scope of your request.

Watch out that you don't forward your SSH agent, since it will be
available to everyone sharing your session. Future versions will
prevent this leakage.

Inspired by [pair.io](http://pair.io).

## Setting up your own

* [Register as a GitHub OAuth application](https://github.com/settings/applications/new)

* Export `$OAUTH_CLIENT_ID` and `$OAUTH_CLIENT_SECRET`

* Generate an SSH keypair with no passphrase: `ssh-keygen -P "" -f sss`

* Export the pubkey as `$PUBLIC_KEY` and the private key as
  `$PRIVATE_KEY` (you will have to replace newlines with "\n")

* Create a PostgreSQL DB and export `$DATABASE_URL` to point to it.

* Create the DB schema with `lein run -m syme.db`.

* Generate 16 random characters and export it as `$SESSION_SECRET`.

## License

Copyright © 2013 Phil Hagelberg

Distributed under the Eclipse Public License, the same as Clojure.
