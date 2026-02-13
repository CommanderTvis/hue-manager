cask "hue-manager" do
  version "0.0.0-aa70748"
  sha256 "2340cd858b8e4186000c298de835b6629230936378da1677584a162a82b2d576"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/354896505",
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
