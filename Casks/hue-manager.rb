cask "hue-manager" do
  version "0.0.0-9992e03"
  sha256 "14693a1d26688c424c2bf1ade8b7b4312c7bc5a84df2822e56099f3438e2ba12"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/371709990",
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
