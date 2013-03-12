# Syme

Instant collaboration on GitHub projects.

> Almost in the act of stepping on board, Gabriel Syme turned to the gaping Gregory.
>
> "You have kept your word," he said gently, with his face in shadow.
> "You are a man of honour, and I thank you. You have kept it even down
> to a small particular. There was one special thing you promised me at
> the beginning of the affair, and which you have certainly given me by
> the end of it."
> 
> "What do you mean?" cried the chaotic Gregory. "What did I promise you?"
> 
> "A very entertaining evening," said Syme, and he made a military
> salute with the sword-stick as the steamboat slid away.

- The Man who was Thursday, by G.K. Chesterton

## Usage

1. Enter the name of a GitHub repo.
   (Authorize Syme via GitHub if you haven't already.)
2. Enter your AWS credentials and names of GitHub users to invite.
4. SSH into the instance once it's booted using the command shown and launch `tmux`.
5. Send the login info to the users you have invited.

Syme handles launching the instance, setting up public keys, and
cloning the repository in question.

Your AWS credentials are kept in an encrypted cookie in your browser
and aren't stored server-side beyond the scope of your request.

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

* Export `CANONICAL_URL` as the fully-qualified URL of the splash page.

Optional:

* Sign up for Amazon Route53 and export `$AWS_ACCESS_KEY` and `$AWS_SECRET_KEY`.

* Register a domain and export it as `$SUBDOMAIN` formatted like
  "%s.%s.syme.in". The `%s` places will be filled with the instance
  owner and instance id.

* Host the DNS under Route53 and export the `$ZONE_ID`.

## License

Copyright © 2013 Phil Hagelberg

Distributed under the Eclipse Public License, the same as Clojure.
