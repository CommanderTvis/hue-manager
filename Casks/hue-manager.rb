cask "hue-manager" do
  version "2026.03.27-6d6e3b2"
  sha256 "c1c95fbed880dc437f3122348b7f0e273e411bf04c5815d3c6ae97c4739dc43d"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/383071616",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end
