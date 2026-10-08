AppSettingsPage({
  build(props) {
    return View({}, [
      Section({
        title: 'Training Hub',
        description:
          'No Android, abre Training Hub > Mais > Active 2, ativa o bridge e copia o código de 6 dígitos.',
      }, [
        TextInput({
          label: 'Código de emparelhamento',
          settingsKey: 'pairing_code',
          placeholder: '123456',
        }),
      ]),
      Section({
        title: 'Ligação',
        description:
          'A Mini App comunica por Bluetooth com a app Zepp. O Side Service da Zepp encaminha os pedidos para o Training Hub no próprio telemóvel. Nenhum servidor externo é necessário.',
      }, []),
    ])
  },
})
