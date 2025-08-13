import requests
import json

imieList =[
                 ("354876543210123", "device-1"),
                  ("490154203237518", "device-2"),
                  ("861075030123456", "device-3"),
                  ("356938035643809", "device-4"),
                  ("869470028364715", "device-5"),
                  ("352044090876543", "device-6"),
                  ("359112078654321", "device-7"),
                  ("862549036547890", "device-8"),
                  ("358967054321098", "device-9"),
                  ("864209037654321", "device-10"),
                  ("357924056789012", "device-11"),
                  ("865432098765432", "device-12"),
                  ("351987065432109", "device-13"),
                  ("867890123456789", "device-14"),
                  ("356789012345678", "device-15"),
                  ("869012345678901", "device-16"),
                  ("354321098765432", "device-17"),
                  ("862109876543210", "device-18"),
                  ("358765432109876", "device-19"),
                  ("864567890123456", "device-20"),
                  ("357654321098765", "device-21"),
                  ("865678901234567", "device-22"),
                  ("352345678901234", "device-23"),
                  ("867789012345678", "device-24"),
                  ("359876543210987", "device-25"),
                  ("868901234567890", "device-26"),
                  ("354567890123456", "device-27"),
                  ("862345678901234", "device-28"),
                  ("358901234567890", "device-29"),
                  ("864321098765432", "device-30"),
                  ("357890123456789", "device-31"),
                  ("865432109876543", "device-32"),
                  ("351234567890123", "device-33"),
                  ("867654321098765", "device-34"),
                  ("356543210987654", "device-35"),
                  ("869876543210987", "device-36"),
                  ("354098765432109", "device-37"),
                  ("862210987654321", "device-38"),
                  ("358432109876543", "device-39"),
                  ("864654321098765", "device-40"),
                  ("357876543210987", "device-41"),
                  ("865098765432109", "device-42"),
                  ("352109876543210", "device-43"),
                  ("867321098765432", "device-44"),
                  ("359543210987654", "device-45"),
                  ("868765432109876", "device-46"),
                  ("354987654321098", "device-47"),
                  ("862109876543210", "device-48"),
                  ("358321098765432", "device-49"),
                  ("864543210987654", "device-50"),
                  ("357765432109876", "device-51"),
                  ("865987654321098", "device-52"),
                  ("352210987654321", "device-53"),
                  ("867432109876543", "device-54"),
                  ("359654321098765", "device-55"),
                  ("868876543210987", "device-56"),
                  ("354210987654321", "device-57"),
                  ("862432109876543", "device-58"),
                  ("358654321098765", "device-59"),
                  ("864876543210987", "device-60"),
                  ("357098765432109", "device-61"),
                  ("865210987654321", "device-62"),
                  ("352432109876543", "device-63"),
                  ("867654321098765", "device-64"),
                  ("359876543210987", "device-65"),
                  ("868098765432109", "device-66"),
                  ("354321098765432", "device-67"),
                  ("862543210987654", "device-68"),
                  ("358765432109876", "device-69"),
                  ("864987654321098", "device-70"),
                  ("357210987654321", "device-71"),
                  ("865432109876543", "device-72"),
                  ("352654321098765", "device-73"),
                  ("867876543210987", "device-74"),
                  ("359098765432109", "device-75"),
                  ("868210987654321", "device-76"),
                  ("354432109876543", "device-77"),
                  ("862654321098765", "device-78"),
                  ("358876543210987", "device-79"),
                  ("864098765432109", "device-80"),
                  ("357321098765432", "device-81"),
                  ("865543210987654", "device-82"),
                  ("352765432109876", "device-83"),
                  ("867987654321098", "device-84"),
                  ("359210987654321", "device-85"),
                  ("868432109876543", "device-86"),
                  ("354654321098765", "device-87"),
                  ("862876543210987", "device-88"),
                  ("358098765432109", "device-89"),
                  ("864210987654321", "device-90"),
                  ("357432109876543", "device-91"),
                  ("865654321098765", "device-92"),
                  ("352876543210987", "device-93"),
                  ("867098765432109", "device-94"),
                  ("359321098765432", "device-95"),
                  ("868543210987654", "device-96"),
                  ("354765432109876", "device-97"),
                  ("862987654321098", "device-98"),
                  ("358210987654321", "device-99"),
                  ("864432109876543", "device-100")
          ]

def add_device(id, imie, strName):
#     url = "http://localhost:8082/api/devices"
    url = "https://iotstagingenv.duckdns.org/api/devices"

    payload = json.dumps({
      "id": id,
      "name": strName,
      "uniqueId": imie,
      "status": "unknown",
      "disabled": "false",
      "lastUpdate": None,
      "positionId": None,
      "groupId": 12,
      "phone": None,
      "model": None,
      "contact": None,
      "category": None,
      "attributes": {}
    })
    headers = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Authorization': 'Bearer RzBFAiEAzUg42zjOkcwm25uJ4z7u4U3gbZvAbXspR__jrO1q3b0CIBuR5dZby9KwmIZUgzoRcOcZ569Za5NJGrE7CDFFopKIeyJ1IjozNCwiZSI6IjIwMjUtMDgtMTNUMTg6MzA6MDAuMDAwKzAwOjAwIn0',
      'Cookie': 'JSESSIONID=node01ufj26g87be15lql5ei6t97wc9122.node0'
    }

    response = requests.request("POST", url, headers=headers, data=payload)

    print(response.text)
    print(response.status_code)
    return response.status_code

id = 0

for imie in imieList:

    status = add_device(id, imie[0], imie[1])
    if status == 200:
        id = id + 1
