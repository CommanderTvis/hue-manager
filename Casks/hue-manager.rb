cask "hue-manager" do
  version "2026.05.30-d0f1a22"
  sha256 "41ad85166816f6c8475a5aa5e24519183a263dba48f79d7669e8a99e85c0702c"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/433825196",
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
