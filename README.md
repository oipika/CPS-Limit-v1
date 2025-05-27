# CPS Limiter

Spigot Plugin which limits the rate a minecraft player can interact with the world to lessen the advantage of autoclickers.

## Installation

1) Download the zip file & extract the content
2) In the main folder with the pom file and other contents, create a cmd line in that directory & type `mvn clean install`
3) Once you successfully build the file, go the target folder and there you will find the plugin.


## Plugin Configuration

In `plugins/CPSLimiter/config.yml` from your Spigot server directory, there are the following options:

- CPS Threshold: [Integer] (default 20) - The amount of interactions a player can trigger every second before hitting max threshold.

- Hit Miss Cooldown: [Integer] (default 2.0) - The amount of time the player has to wait before being able to hit again after being penalized for exceeding the max cps threshold.


